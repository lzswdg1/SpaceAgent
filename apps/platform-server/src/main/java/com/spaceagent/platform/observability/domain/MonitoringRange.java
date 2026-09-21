package com.spaceagent.platform.observability.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public enum MonitoringRange {
    TODAY("today", "hour"),
    ONE_DAY("1d", "hour"),
    SEVEN_DAYS("7d", "day"),
    THIRTY_DAYS("30d", "day"),
    ALL("all", "month");

    private final String wireName;
    private final String bucketSize;

    MonitoringRange(String wireName, String bucketSize) {
        this.wireName = wireName;
        this.bucketSize = bucketSize;
    }

    public String wireName() {
        return wireName;
    }

    public String bucketSize() {
        return bucketSize;
    }

    public Instant since(Instant now) {
        return switch (this) {
            case ALL -> null;
            case ONE_DAY -> now.minus(1, ChronoUnit.DAYS);
            case TODAY -> now.atZone(ZoneOffset.UTC).toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            case SEVEN_DAYS -> now.minus(7, ChronoUnit.DAYS);
            case THIRTY_DAYS -> now.minus(30, ChronoUnit.DAYS);
        };
    }

    public static MonitoringRange fromWire(String value) {
        String normalized = value == null || value.isBlank() ? "7d" : value.trim();
        for (MonitoringRange range : values()) {
            if (range.wireName.equalsIgnoreCase(normalized)) return range;
        }
        throw new IllegalArgumentException("Unsupported monitoring range: " + value);
    }
}
