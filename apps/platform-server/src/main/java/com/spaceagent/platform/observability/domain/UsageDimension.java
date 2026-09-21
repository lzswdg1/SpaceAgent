package com.spaceagent.platform.observability.domain;

public enum UsageDimension {
    ORGANIZATION("org"),
    AGENT("agent"),
    MODEL("model"),
    PROVIDER("provider");

    private final String wireName;

    UsageDimension(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static UsageDimension fromWire(String value) {
        String normalized = value == null || value.isBlank() ? "agent" : value.trim();
        for (UsageDimension dimension : values()) {
            if (dimension.wireName.equalsIgnoreCase(normalized)) return dimension;
        }
        throw new IllegalArgumentException("Unsupported usage group: " + value);
    }
}
