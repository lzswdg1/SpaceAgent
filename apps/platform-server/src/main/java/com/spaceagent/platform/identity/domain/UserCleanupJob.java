package com.spaceagent.platform.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record UserCleanupJob(
        String userId,
        UUID commandId,
        UUID requestedBy,
        String reasonHash,
        UserCleanupJobState state,
        Instant retentionNotBefore,
        Instant nextAttemptAt,
        int attempt,
        int maxAttempts,
        String leaseOwner,
        String leaseToken,
        long fencingToken,
        Instant leaseUntil,
        String lastErrorCode,
        String lastErrorSummary,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
    public boolean claimed() {
        return state == UserCleanupJobState.CLAIMED;
    }
}
