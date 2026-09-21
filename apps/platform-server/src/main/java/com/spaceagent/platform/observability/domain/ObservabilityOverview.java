package com.spaceagent.platform.observability.domain;

public record ObservabilityOverview(
        long totalOrganizations,
        long totalAgents,
        long totalSessions,
        long activeSessions,
        long idleSessions,
        long closedSessions,
        long runtimeActiveSessions,
        long totalInputTokens,
        long totalOutputTokens,
        long totalCacheReadTokens,
        long totalCacheCreateTokens,
        Long totalCostMicros,
        long peakConcurrent,
        boolean incompleteCost) {
}
