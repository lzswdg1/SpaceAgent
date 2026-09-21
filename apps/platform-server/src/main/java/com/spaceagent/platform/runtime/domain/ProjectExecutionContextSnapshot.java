package com.spaceagent.platform.runtime.domain;

import java.time.Instant;

/** Immutable, content-addressed evidence captured for one directory-bound Coding Run. */
public record ProjectExecutionContextSnapshot(
        String id,
        String tenantId,
        String ownerId,
        String projectId,
        String projectDirectoryId,
        String conversationId,
        String taskId,
        String taskPlanId,
        String planStepId,
        String agentRunId,
        String runConfigurationSnapshotId,
        String workspaceId,
        String blueprintId,
        Integer blueprintVersion,
        String conversationContextSnapshotId,
        Integer conversationContextSnapshotVersion,
        String checkpointId,
        String idempotencyHash,
        String inputHash,
        String snapshotHash,
        String payloadJson,
        Instant createdAt) {

    public ProjectExecutionContextSnapshot {
        require(id, "id");
        require(tenantId, "tenantId");
        require(ownerId, "ownerId");
        require(projectId, "projectId");
        require(projectDirectoryId, "projectDirectoryId");
        require(conversationId, "conversationId");
        require(taskId, "taskId");
        require(taskPlanId, "taskPlanId");
        require(planStepId, "planStepId");
        require(agentRunId, "agentRunId");
        require(runConfigurationSnapshotId, "runConfigurationSnapshotId");
        require(workspaceId, "workspaceId");
        require(idempotencyHash, "idempotencyHash");
        require(inputHash, "inputHash");
        require(snapshotHash, "snapshotHash");
        require(payloadJson, "payloadJson");
        if ((blueprintId == null) != (blueprintVersion == null)
                || (conversationContextSnapshotId == null)
                    != (conversationContextSnapshotVersion == null)) {
            throw new IllegalArgumentException("Optional recovery references require their version");
        }
        if ((blueprintVersion != null && blueprintVersion < 1)
                || (conversationContextSnapshotVersion != null
                    && conversationContextSnapshotVersion < 0)) {
            throw new IllegalArgumentException("Recovery reference versions are invalid");
        }
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
