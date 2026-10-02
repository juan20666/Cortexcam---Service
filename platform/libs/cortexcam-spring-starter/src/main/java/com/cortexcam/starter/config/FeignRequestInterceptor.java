package com.cortexcam.starter.config;

import com.cortexcam.starter.tracing.TraceIdProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
public class FeignRequestInterceptor implements RequestInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void apply(RequestTemplate template) {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder
                    .getRequestAttributes();

            if (attributes == null) {
                log.debug("RequestContextHolder no tiene atributos - posible contexto no HTTP");
                return;
            }

            HttpServletRequest request = attributes.getRequest();
            String authHeader = request.getHeader(AUTHORIZATION_HEADER);

            if (authHeader != null && !authHeader.isEmpty()) {
                template.header(AUTHORIZATION_HEADER, authHeader);
                log.debug("Authorization header propagado a Feign");
            } else {
                log.debug("Authorization header NO encontrado en la solicitud HTTP entrante");
            }

            String traceId = TraceIdProvider.current();
            template.header(TRACE_ID_HEADER, traceId);
            log.debug("X-Trace-Id propagado a Feign: {}", traceId);
        } catch (Exception e) {
            log.error("Error al propagar headers a Feign client: {}", e.getMessage(), e);
        }
    }
}