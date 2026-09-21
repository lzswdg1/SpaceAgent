package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpRegistrySyncJob(
        String id,
        String sourceId,
        String sourceKey,
        String requestedBy,
        McpRegistrySyncState state,
        Instant updatedSince,
        Instant watermarkAt,
        int fetchedCount,
        int snapshotCount,
        int candidateCount,
        String safeErrorCode,
        int attempt,
        String claimOwner,
        String claimToken,
        Instant leaseUntil,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt) {
}
