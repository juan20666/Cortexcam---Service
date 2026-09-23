package com.CortexcamService.infrastructure.config;

import com.CortexcamService.infrastructure.tracing.TraceIdFilter;
import com.CortexcamService.infrastructure.tracing.TraceProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServletFilterConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter(TraceProperties traceProperties) {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new TraceIdFilter(traceProperties));
        bean.setOrder(1);
        bean.addUrlPatterns("/*");
        return bean;
    }
}