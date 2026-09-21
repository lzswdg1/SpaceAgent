package com.spaceagent.platform.observability.domain;

public record ObservabilityUsageRow(
        String key,
        String name,
        String subLabel,
        Long agentCount,
        long sessionCount,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheCreateTokens,
        Long costMicros,
        long callCount,
        double averageLatencyMs,
        boolean incompleteCost) {
}
