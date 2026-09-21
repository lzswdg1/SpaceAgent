package com.spaceagent.platform.runtime.domain;

import java.time.Instant;

/** Durable control record for one autonomous Project PlanStep execution. */
public record ProjectCodingJob(
        String id, String tenantId, String ownerId, String projectId,
        String projectDirectoryId, String conversationId, String sourceRepositoryId,
        String rootTaskId, String taskId, String taskPlanId, String executionId, String planStepId,
        String agentId, String primaryConfigurationHash, String reviewerConfigurationHash, String baseRef,
        String idempotencyHash, String inputHash, ProjectCodingJobState state,
        String workspaceId, String codingRunId, String reviewerRunId,
        int iteration, int reviewRound, String contextJson, String pendingToolJson,
        String pendingApprovalId, String patchArtifactId, String commitArtifactId,
        String reviewId, String sourceMergeId, String safeErrorCode,
        int attempt, String claimOwner, String claimToken, long fencingToken, Instant leaseUntil,
        long revision, Instant createdAt, Instant startedAt, Instant updatedAt, Instant completedAt,
        String reviewerAgentId) {
    public ProjectCodingJob {
        require(id, "id"); require(tenantId, "tenantId"); require(ownerId, "ownerId");
        require(projectId, "projectId"); require(projectDirectoryId, "projectDirectoryId");
        require(conversationId, "conversationId"); require(sourceRepositoryId, "sourceRepositoryId");
        require(rootTaskId, "rootTaskId"); require(taskId, "taskId");
        require(taskPlanId, "taskPlanId"); require(planStepId, "planStepId");
        require(agentId, "agentId");
        primaryConfigurationHash = optional(primaryConfigurationHash);
        reviewerConfigurationHash = optional(reviewerConfigurationHash);
        reviewerAgentId = optional(reviewerAgentId);
        require(baseRef, "baseRef");
        require(idempotencyHash, "idempotencyHash"); require(inputHash, "inputHash");
        if (state == null || iteration < 0 || reviewRound < 0 || attempt < 0
                || fencingToken < 0 || revision < 1 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Project Coding Job lifecycle is invalid");
        }
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
