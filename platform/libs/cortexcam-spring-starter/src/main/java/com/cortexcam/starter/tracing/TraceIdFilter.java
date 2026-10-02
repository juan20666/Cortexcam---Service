package com.cortexcam.starter.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("asset-service");
    private final TraceProperties traceProperties;

    public TraceIdFilter(TraceProperties traceProperties) {
        this.traceProperties = traceProperties;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            String incoming = request.getHeader(traceProperties.getTraceIdHeader());

            String traceId = (incoming != null && !incoming.isBlank())
                    ? incoming.trim()
                    : generateTraceId();

            MDC.put(TraceConstants.TRACE_ID, traceId);
            response.setHeader(traceProperties.getTraceIdHeader(), traceId);
            log.info("Procesando: {} {}", request.getMethod(), request.getRequestURI());
            filterChain.doFilter(request, response);
            log.info("Solicitud completada: {} {}", request.getMethod(), request.getRequestURI());
        } catch (Exception e) {
            log.error("Error en TraceIdFilter: ", e);
            throw e;
        } finally {
            MDC.clear();
        }
    }

    private String generateTraceId() {
        String uuid8 = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase();
        return traceProperties.getPrefix() + " " + uuid8;
    }
}