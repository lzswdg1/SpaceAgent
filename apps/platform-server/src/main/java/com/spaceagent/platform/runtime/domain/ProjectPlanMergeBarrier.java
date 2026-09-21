package com.spaceagent.platform.runtime.domain;

import java.time.Instant;

/** Runtime cursor for a stable, reviewed local merge queue; it never performs Git effects. */
public record ProjectPlanMergeBarrier(String executionId, String tenantId, String ownerId, String projectId,
                                      String taskPlanId, int nextApplyIndex, State state, long revision,
                                      Instant createdAt, Instant updatedAt, Instant completedAt) {
    public ProjectPlanMergeBarrier {
        if (executionId == null || tenantId == null || ownerId == null || projectId == null || taskPlanId == null
                || nextApplyIndex < 0 || state == null || revision < 1 || createdAt == null || updatedAt == null) throw new IllegalArgumentException("merge barrier is invalid");
    }
    public enum State { ACTIVE, COMPLETED, BLOCKED }
}
