package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.Objects;

/** Durable user-directed continuation between Project Coding jobs. */
public record ProjectRunHandoff(
        String id,
        String tenantId,
        String ownerId,
        String projectId,
        String projectDirectoryId,
        String sourceRepositoryId,
        String rootTaskId,
        String taskId,
        String taskPlanId,
        String planStepId,
        String baseRef,
        String sourceCodingJobId,
        String sourceAgentRunId,
        String workspaceId,
        String recoverySnapshotId,
        String recoverySnapshotHash,
        String targetConversationId,
        String targetAgentId,
        String reviewerAgentId,
        String targetCodingJobId,
        String targetAgentRunId,
        String idempotencyHash,
        String inputHash,
        ProjectRunHandoffState state,
        String memoryKey,
        String safeErrorCode,
        int attempt,
        String claimOwner,
        String claimToken,
        long fencingToken,
        Instant leaseUntil,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public ProjectRunHandoff {
        Objects.requireNonNull(state, "state");
        if (revision < 1 || attempt < 0 || fencingToken < 0) {
            throw new IllegalArgumentException("Invalid Project Run Handoff counters");
        }
    }
}
