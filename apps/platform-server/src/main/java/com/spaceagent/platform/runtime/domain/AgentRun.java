package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;

/**
 * The durable root entity for one agent execution. AgentRun references an
 * AgentDefinition and a project task, but does not own their state.
 */
public record AgentRun(
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

    public AgentRun {
        chatTaskId = chatTaskId == null || chatTaskId.isBlank() ? null : chatTaskId;
        executionCursor = executionCursor == null
                ? ExecutionCursor.initial()
                : executionCursor;
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }

    /** Compatibility constructor before standalone Chat Task pinning. */
    public AgentRun(
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

    /** Compatibility constructor before ProjectDirectory/Workspace run binding. */
    public AgentRun(
            String id, String agentId, String configurationSnapshotId, String tenantId,
            String ownerId, String conversationId, String projectId, String taskId,
            String taskPlanId, String planStepId, ExecutionCursor executionCursor,
            long revision, AgentRunState state, String failureReason,
            Instant createdAt, Instant updatedAt, Instant completedAt) {
        this(id, agentId, configurationSnapshotId, tenantId, ownerId, conversationId, null, projectId,
                null, null, taskId, taskPlanId, planStepId, executionCursor, revision,
                state, failureReason, createdAt, updatedAt, completedAt);
    }

    /** Compatibility constructor for runs created before canonical Task binding. */
    public AgentRun(
            String id,
            String agentId,
            String configurationSnapshotId,
            String ownerId,
            String conversationId,
            String projectId,
            String taskId,
            AgentRunState state,
            String failureReason,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
        this(id, agentId, configurationSnapshotId, null, ownerId, conversationId, null,
                projectId, null, null, taskId, null, null, ExecutionCursor.initial(), 0,
                state, failureReason, createdAt, updatedAt, completedAt);
    }

    public boolean taskScoped() {
        return tenantId != null && projectId != null && taskId != null
                && taskPlanId != null && planStepId != null;
    }

    public boolean workspaceScoped() {
        return projectDirectoryId != null && workspaceId != null;
    }
}
