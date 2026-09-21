package com.spaceagent.platform.observability.domain;

import java.time.Instant;

public record ObservabilitySession(
        String id,
        String name,
        String agentId,
        String agentName,
        String organizationId,
        String organizationName,
        String providerName,
        boolean runtimeActive,
        long inputTokens,
        long outputTokens,
        String status,
        long durationSeconds,
        Instant lastActivityAt,
        long messageCount,
        boolean processing,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        String closeReason,
        String source) {
}
