package com.spaceagent.platform.observability.domain;

import java.time.Instant;
import java.util.Map;

public record TraceSpan(
        String id,
        String traceId,
        String parentSpanId,
        TraceSpanType spanType,
        String name,
        TraceStatus status,
        boolean error,
        String errorMessage,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        String model,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        Long cacheCreateTokens,
        Long cacheReadTokens,
        String inputPreview,
        String outputPreview,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public TraceSpan {
        metadata = metadata == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }
}
