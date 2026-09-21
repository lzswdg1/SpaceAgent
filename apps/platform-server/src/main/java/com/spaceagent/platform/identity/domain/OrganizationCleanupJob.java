package com.spaceagent.platform.identity.domain;

import java.time.Instant;

/** Durable cleanup coordination root. Resource deletion remains with owning modules. */
public record OrganizationCleanupJob(
        String organizationId,
        OrganizationCleanupJobState state,
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
        return state == OrganizationCleanupJobState.CLAIMED;
    }
}
