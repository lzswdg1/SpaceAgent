package com.spaceagent.platform.runtime.api;

import java.util.Map;

public record ChatRuntimeEvent(String type, Map<String, Object> data) {
    public ChatRuntimeEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type must not be blank");
        }
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
