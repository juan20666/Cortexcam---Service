package com.CortexcamService.infrastructure.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;

/**
 * Logs mutating HTTP requests (POST/PUT/PATCH/DELETE) with actor user id when
 * available.
 */
@Component
@Order(50)
public class MutationLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("asset-service");
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String method = request.getMethod();
        if (method == null || !MUTATING.contains(method.toUpperCase(Locale.ROOT))) {
            return true;
        }
        String path = resolvePath(request);
        return path.startsWith("/actuator")
                || path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/webjars")
                || path.equals("/health")
                || path.startsWith("/health/");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            String action = mapAction(request.getMethod());
            String resource = inferResource(resolvePath(request));
            String resourceId = inferResourceId(resolvePath(request));
            String actor = resolveActor();
            String company = firstNonBlank(MDC.get(TraceConstants.COMPANY_ID), "-");
            MutationLog.info(
                    log,
                    action,
                    resource,
                    resourceId,
                    actor,
                    company,
                    "method=" + request.getMethod()
                            + " status=" + response.getStatus()
                            + " uri=" + resolvePath(request));
        }
    }

    private static String mapAction(String method) {
        if (method == null) {
            return "MUTATE";
        }
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> "MUTATE";
        };
    }

    private static String inferResource(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "unknown";
        }
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part == null || part.isBlank() || "api".equals(part) || "manager".equals(part)
                    || "v1".equals(part) || "v2".equals(part) || "internal".equals(part)) {
                continue;
            }
            if (looksLikeId(part)) {
                continue;
            }
            return part;
        }
        return "unknown";
    }

    private static String inferResourceId(String path) {
        if (path == null || path.isBlank()) {
            return "-";
        }
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (looksLikeId(parts[i])) {
                return parts[i];
            }
        }
        return "-";
    }

    private static boolean looksLikeId(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.matches("\\d+")) {
            return true;
        }
        return value.matches("(?i)[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}");
    }

    private static String resolveActor() {
        String mdcUser = MDC.get(TraceConstants.USER_ID);
        if (mdcUser != null && !mdcUser.isBlank()) {
            return mdcUser;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "ANONYMOUS";
        }
        Object principal = auth.getPrincipal();
        if (principal == null) {
            return "UNKNOWN";
        }
        Object userId = invokeNoArg(principal, "getUserId");
        if (userId != null) {
            return String.valueOf(userId);
        }
        Object userIdMethod = invokeNoArg(principal, "userId");
        if (userIdMethod != null) {
            return String.valueOf(userIdMethod);
        }
        Object claim = invokeOneArg(principal, "getClaimAsString", String.class, "userId");
        if (claim != null) {
            return String.valueOf(claim);
        }
        if (principal instanceof String s && !s.isBlank() && !"anonymousUser".equalsIgnoreCase(s)) {
            return s;
        }
        String name = auth.getName();
        return (name == null || name.isBlank() || "anonymousUser".equalsIgnoreCase(name)) ? "UNKNOWN" : name;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object invokeOneArg(Object target, String methodName, Class<?> argType, Object arg) {
        try {
            Method method = target.getClass().getMethod(methodName, argType);
            return method.invoke(target, arg);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String resolvePath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isBlank()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            String relative = uri.substring(contextPath.length());
            return relative.isEmpty() ? "/" : relative;
        }
        return uri;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}