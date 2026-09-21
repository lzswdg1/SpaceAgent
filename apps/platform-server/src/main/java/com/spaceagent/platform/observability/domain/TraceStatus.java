package com.spaceagent.platform.observability.domain;

import java.util.Locale;

public enum TraceStatus {
    RUNNING,
    SUCCESS,
    ERROR,
    ABORTED,
    RETRY_ABORTED;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TraceStatus fromWire(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return TraceStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
