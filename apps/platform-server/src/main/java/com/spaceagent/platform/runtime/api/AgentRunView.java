package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;

import java.time.Instant;

/**
 * Public agent-run query result.
 */
public record AgentRunView(
        String id,
        String agentId,
        String configurationSnapshotId,
        String tenantId,
        String ownerId,
        String conversationId,
        String chatTaskId,
        String projectId,
        String projectDirectoryId,
        String workspaceId,
        String taskId,
        String taskPlanId,
        String planStepId,
        ExecutionCursor executionCursor,
        long revision,
        AgentRunState state,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public AgentRunView(
            String id, String agentId, String configurationSnapshotId, String tenantId,
            String ownerId, String conversationId, String projectId,
            String projectDirectoryId, String workspaceId, String taskId,
            String taskPlanId, String planStepId, ExecutionCursor executionCursor,
            long revision, AgentRunState state, String failureReason,
            Instant createdAt, Instant updatedAt, Instant completedAt) {
        this(id, agentId, configurationSnapshotId, tenantId, ownerId, conversationId, null,
                projectId, projectDirectoryId, workspaceId, taskId, taskPlanId, planStepId,
                executionCursor, revision, state, failureReason, createdAt, updatedAt, completedAt);
    }

    public AgentRunView(
            String id, String agentId, String configurationSnapshotId, String tenantId,
            String ownerId, String conversationId, String projectId, String taskId,
            String taskPlanId, String planStepId, ExecutionCursor executionCursor,
            long revision, AgentRunState state, String failureReason,
            Instant createdAt, Instant updatedAt, Instant completedAt) {
        this(id, agentId, configurationSnapshotId, tenantId, ownerId, conversationId, null, projectId,
                null, null, taskId, taskPlanId, planStepId, executionCursor, revision,
                state, failureReason, createdAt, updatedAt, completedAt);
    }
}
