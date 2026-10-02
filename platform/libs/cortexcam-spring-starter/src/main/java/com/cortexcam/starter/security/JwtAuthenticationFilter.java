package com.cortexcam.starter.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final SecretKey signingKey;

    public JwtAuthenticationFilter(@Value("${jwt.secret}") String jwtSecret) {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("jwt.secret is required to verify HS256 tokens");
        }
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/actuator")
                || path.equals("/health")
                || path.startsWith("/health/")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/webjars")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<Parsed> parsed = parseJwt(authHeader.substring(7).trim());
        if (parsed.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        Parsed p = parsed.get();
        org.slf4j.MDC.put(com.cortexcam.starter.tracing.TraceConstants.USER_ID, p.userId());
        if (p.companyId() != null && !p.companyId().isBlank()) {
            org.slf4j.MDC.put(com.cortexcam.starter.tracing.TraceConstants.COMPANY_ID, p.companyId());
        }
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                new UserJwtPrincipal(p.userId(), p.email(), p.documentNumber()), null, p.authorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
        filterChain.doFilter(request, response);
    }

    private Optional<Parsed> parseJwt(String rawJwt) {
        if (rawJwt.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(rawJwt)
                    .getPayload();
            return parseVerifiedClaims(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<Parsed> parseVerifiedClaims(Claims claims) {
        Object userIdObj = claims.get("userId");
        if (userIdObj == null || userIdObj.toString().isBlank()) {
            return Optional.empty();
        }
        String userId = userIdObj.toString().trim();

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String r : stringListClaim(claims.get("roles"))) {
            addRoleAuthorities(authorities, r);
        }
        for (String perm : stringListClaim(claims.get("permissions"))) {
            if (perm == null || perm.isBlank() || "PERM_ALL".equalsIgnoreCase(perm.trim())) {
                continue;
            }
            authorities.add(new SimpleGrantedAuthority(perm.trim()));
        }
        Object companyId = claims.get("companyId");
        String companyIdValue = null;
        if (companyId != null && !companyId.toString().isBlank()) {
            companyIdValue = companyId.toString().trim();
            authorities.add(new SimpleGrantedAuthority("COMPANY_ID_" + companyIdValue));
        }

        return Optional.of(new Parsed(
                userId, companyIdValue, claimEmail(claims), blankToNull(claims.getSubject()),
                Collections.unmodifiableList(authorities)));
    }

    private record Parsed(String userId, String companyId, String email, String documentNumber,
            List<SimpleGrantedAuthority> authorities) {
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String claimEmail(Map<String, Object> claims) {
        Object email = claims.get("email");
        if (email == null || email.toString().isBlank()) {
            email = claims.get("preferred_username");
        }
        if (email == null || email.toString().isBlank()) {
            return null;
        }
        String value = email.toString().trim();
        return value.contains("@") ? value : null;
    }

    private static List<String> stringListClaim(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        if (raw instanceof String s && !s.isBlank()) {
            return List.of(s.trim());
        }
        return List.of();
    }

    private static void addRoleAuthorities(List<SimpleGrantedAuthority> authorities, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String upper = raw.trim().toUpperCase();
        String withPrefix = upper.startsWith("ROLE_") ? upper : "ROLE_" + upper;
        authorities.add(new SimpleGrantedAuthority(withPrefix));
    }
}