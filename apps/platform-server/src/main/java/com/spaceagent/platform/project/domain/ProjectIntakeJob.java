package com.spaceagent.platform.project.domain;

import java.time.Instant;

/** Durable pre-Task Project analysis and proposal evidence. */
public record ProjectIntakeJob(
        String id,
        String tenantId,
        String ownerId,
        String projectId,
        String projectDirectoryId,
        String sourceRepositoryId,
        String conversationId,
        String agentId,
        String goal,
        String idempotencyHash,
        String inputHash,
        ProjectIntakeState state,
        int attempt,
        String claimOwner,
        String claimToken,
        long fencingToken,
        Instant leaseUntil,
        String workspaceRef,
        String sourceHeadCommit,
        String inspectionHash,
        String inspectionJson,
        String proposalHash,
        String proposalJson,
        String agentRunId,
        String blueprintId,
        String rootTaskId,
        String taskPlanId,
        String safeErrorCode,
        String reviewedBy,
        String reviewReason,
        Instant reviewedAt,
        long revision,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        Instant workspaceCleanedAt) {

    public ProjectIntakeJob {
        require(id, "id");
        require(tenantId, "tenantId");
        require(ownerId, "ownerId");
        require(projectId, "projectId");
        require(projectDirectoryId, "projectDirectoryId");
        require(sourceRepositoryId, "sourceRepositoryId");
        require(agentId, "agentId");
        require(goal, "goal");
        require(idempotencyHash, "idempotencyHash");
        require(inputHash, "inputHash");
        if (state == null || attempt < 0 || fencingToken < 0 || revision < 1
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Project intake lifecycle is invalid");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
