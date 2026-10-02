package com.cortexcam.starter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class InternalServiceTokenAuthFilter extends OncePerRequestFilter {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String SERVICE_NAME_HEADER = "X-Service-Name";

    @Value("${app.internal-service-token:}")
    private String expectedToken;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String path = servletPath != null && !servletPath.isEmpty() ? servletPath : request.getRequestURI();
        return path == null || !path.startsWith("/v1/internal/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (expectedToken == null || expectedToken.isEmpty()) {
            log.warn("app.internal-service-token no configurado — rechazando {}", request.getServletPath());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Internal token not configured");
            return;
        }

        String token = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (token == null || !expectedToken.equals(token.trim())) {
            log.warn("X-Internal-Token inválido o faltante en {}", request.getServletPath());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing internal token");
            return;
        }

        String serviceName = request.getHeader(SERVICE_NAME_HEADER);
        if (serviceName == null || serviceName.isBlank()) {
            log.warn("X-Service-Name faltante en request a {}", request.getServletPath());
            serviceName = "UNKNOWN_SERVICE";
        }

        ServiceAccountAuthentication auth = new ServiceAccountAuthentication(serviceName, token);
        SecurityContextHolder.getContext().setAuthentication(auth);

        log.debug("Service '{}' autenticado para {}", serviceName, request.getServletPath());
        filterChain.doFilter(request, response);
    }
}