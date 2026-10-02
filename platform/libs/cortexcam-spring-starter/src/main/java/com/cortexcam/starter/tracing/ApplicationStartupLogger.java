package com.cortexcam.starter.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ansi.AnsiColor;
import org.springframework.boot.ansi.AnsiOutput;
import org.springframework.boot.ansi.AnsiStyle;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ApplicationStartupLogger {

    private static final Logger log = LoggerFactory.getLogger("asset-service");

    private final Environment environment;
    private volatile boolean webServerLogged;
    private volatile boolean readyLogged;

    public ApplicationStartupLogger(Environment environment) {
        this.environment = environment;
        MDC.put(TraceConstants.TRACE_ID, TraceConstants.NO_TRACE);
        log.info("Spring Boot started");
    }

    @EventListener(WebServerInitializedEvent.class)
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        if (webServerLogged) {
            return;
        }
        webServerLogged = true;
        MDC.put(TraceConstants.TRACE_ID, TraceConstants.NO_TRACE);
        String[] profiles = environment.getActiveProfiles();
        String profileLabel = profiles.length > 0 ? String.join(",", profiles) : "default";
        log.info("Profile: {}", profileLabel);
        log.info("Tomcat started on port {}", event.getWebServer().getPort());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationReady() {
        if (readyLogged) {
            return;
        }
        readyLogged = true;
        MDC.put(TraceConstants.TRACE_ID, TraceConstants.NO_TRACE);
        if (environment.matchesProfiles("prod")) {
            log.info("Application started successfully");
        } else {
            log.info("{}", AnsiOutput.toString(AnsiStyle.BOLD, AnsiColor.BRIGHT_GREEN,
                    "Application started successfully"));
        }
        MDC.remove(TraceConstants.TRACE_ID);
    }
}