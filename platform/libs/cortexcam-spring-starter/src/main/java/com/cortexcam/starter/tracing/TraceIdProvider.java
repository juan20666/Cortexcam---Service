package com.cortexcam.starter.tracing;

import org.slf4j.MDC;

public final class TraceIdProvider {

    private TraceIdProvider() {
    }

    public static String current() {
        String traceId = MDC.get(TraceConstants.TRACE_ID);
        return traceId != null ? traceId : TraceConstants.NO_TRACE;
    }

    public static boolean hasTrace() {
        return MDC.get(TraceConstants.TRACE_ID) != null;
    }

    public static void setSystemTrace() {
        MDC.put(TraceConstants.TRACE_ID, TraceConstants.SYSTEM_TRACE);
    }

    public static void clear() {
        MDC.clear();
    }
}