package com.spaceagent.platform.observability.domain;

import java.time.Instant;
import java.util.Map;

public record TraceSummary(
        String id,
        String organizationId,
        String sessionId,
        String sessionName,
        String agentId,
        String agentName,
        String userId,
        String rootSpanId,
        TraceStatus status,
        boolean error,
        String errorMessage,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        Long firstTokenMs,
        long llmMs,
        long toolWallMs,
        long toolDurationSumMs,
        int spanCount,
        int llmTurns,
        int toolCalls,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long cacheCreateTokens,
        long cacheReadTokens,
        Double costUsd,
        boolean costEstimated,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public TraceSummary {
        metadata = metadata == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }
}
