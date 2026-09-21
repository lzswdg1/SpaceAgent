package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.Objects;

/** Fenced lease for a selected ready-wave PlanStep; no Job or Workspace is materialized here. */
public record ProjectPlanWaveClaim(
        String executionId, String taskPlanId, String planStepId, String tenantId, String ownerId,
        String claimOwner, String claimToken, long fencingToken, Instant leaseUntil,
        State state, long revision, Instant createdAt, Instant updatedAt, Instant releasedAt,
        String jobId) {
    public ProjectPlanWaveClaim {
        if (executionId == null || taskPlanId == null || planStepId == null || tenantId == null || ownerId == null
                || claimOwner == null || claimToken == null || fencingToken < 1 || revision < 1
                || leaseUntil == null || state == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("project plan wave claim is invalid");
        }
        if ((state == State.CLAIMED) != (releasedAt == null)) throw new IllegalArgumentException("claim release state is invalid");
    }
    public ProjectPlanWaveClaim(
            String executionId, String taskPlanId, String planStepId, String tenantId,
            String ownerId, String claimOwner, String claimToken, long fencingToken,
            Instant leaseUntil, State state, long revision, Instant createdAt,
            Instant updatedAt, Instant releasedAt) {
        this(executionId, taskPlanId, planStepId, tenantId, ownerId, claimOwner,
                claimToken, fencingToken, leaseUntil, state, revision, createdAt,
                updatedAt, releasedAt, null);
    }
    public enum State { CLAIMED, RELEASED }
}
