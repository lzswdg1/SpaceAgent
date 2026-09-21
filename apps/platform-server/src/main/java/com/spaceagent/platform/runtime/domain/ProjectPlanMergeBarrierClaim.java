package com.spaceagent.platform.runtime.domain;

import java.time.Instant;

/** Lease-fenced ownership of one merge-barrier cursor entry; it never proves a Git effect. */
public record ProjectPlanMergeBarrierClaim(
        String executionId,
        String tenantId,
        String ownerId,
        String projectId,
        String taskPlanId,
        int applyIndex,
        String planStepId,
        String sourceMergeId,
        long barrierRevision,
        int attempt,
        String claimOwner,
        String claimToken,
        long fencingToken,
        Instant leaseUntil) {

    public ProjectPlanMergeBarrierClaim {
        if (executionId == null || tenantId == null || ownerId == null || projectId == null
                || taskPlanId == null || planStepId == null || sourceMergeId == null
                || applyIndex < 0 || barrierRevision < 1 || attempt < 1
                || claimOwner == null || claimOwner.isBlank()
                || claimToken == null || claimToken.isBlank()
                || fencingToken < 1 || leaseUntil == null) {
            throw new IllegalArgumentException("merge barrier claim is invalid");
        }
    }
}
