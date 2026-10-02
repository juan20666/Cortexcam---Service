package com.cortexcam.starter.tracing;

import org.slf4j.Logger;

/**
 * Structured mutation logs for create/update/delete operations.
 * Format: op=ACTION resource=TYPE id=ID actor=USER company=COMPANY [extras]
 */
public final class MutationLog {

    private MutationLog() {
    }

    public static void info(
            Logger log,
            String action,
            String resource,
            Object resourceId,
            Object actorUserId,
            Object companyId,
            String extras) {
        if (log == null || !log.isInfoEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("op=").append(nullToDash(action));
        sb.append(" resource=").append(nullToDash(resource));
        sb.append(" id=").append(nullToDash(resourceId));
        sb.append(" actor=").append(nullToDash(actorUserId));
        sb.append(" company=").append(nullToDash(companyId));
        if (extras != null && !extras.isBlank()) {
            sb.append(' ').append(extras.trim());
        }
        log.info(sb.toString());
    }

    public static void info(
            Logger log,
            String action,
            String resource,
            Object resourceId,
            Object actorUserId,
            Object companyId) {
        info(log, action, resource, resourceId, actorUserId, companyId, null);
    }

    private static String nullToDash(Object value) {
        if (value == null) {
            return "-";
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "-" : text;
    }
}