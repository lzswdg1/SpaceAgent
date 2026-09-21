package com.spaceagent.platform.runtime.domain;

import java.time.Instant;

/** Opaque reviewed SourceMerge evidence in the Runtime stable-apply queue. */
public record ProjectPlanMergeBarrierEntry(String executionId, int applyIndex, String planStepId,
                                           String sourceMergeId, State state, long revision,
                                           Instant createdAt, Instant updatedAt, Instant completedAt) {
    public ProjectPlanMergeBarrierEntry {
        if (executionId == null || planStepId == null || sourceMergeId == null || applyIndex < 0 || state == null
                || revision < 1 || createdAt == null || updatedAt == null) throw new IllegalArgumentException("merge barrier entry is invalid");
    }
    public enum State { READY, APPLIED, BLOCKED, UNKNOWN }
}
