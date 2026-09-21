package com.spaceagent.platform.runtime.api;

/**
 * Public command for starting a durable agent run.
 */
public record StartAgentRunCommand(
        String tenantId,
        String ownerId,
        String agentId,
        String configurationSnapshotId,
        String conversationId,
        String chatTaskId,
        String projectId,
        String projectDirectoryId,
        String workspaceId,
        String taskId,
        String taskPlanId,
        String planStepId,
        String requestedRunId) {

    public StartAgentRunCommand {
        requireNonBlank(ownerId, "ownerId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(conversationId, "conversationId");
        tenantId = normalizeOptional(tenantId);
        configurationSnapshotId = normalizeOptional(configurationSnapshotId);
        chatTaskId = normalizeOptional(chatTaskId);
        projectId = normalizeOptional(projectId);
        projectDirectoryId = normalizeOptional(projectDirectoryId);
        workspaceId = normalizeOptional(workspaceId);
        taskId = normalizeOptional(taskId);
        taskPlanId = normalizeOptional(taskPlanId);
        planStepId = normalizeOptional(planStepId);
        requestedRunId = normalizeOptional(requestedRunId);
        if ((projectId == null) != (taskId == null)) {
            throw new IllegalArgumentException("projectId and taskId must be provided together");
        }
        if (chatTaskId != null && (projectId != null || taskId != null
                || taskPlanId != null || planStepId != null)) {
            throw new IllegalArgumentException(
                    "Chat Task and Project Task scopes are mutually exclusive");
        }
        if ((taskPlanId == null) != (planStepId == null)) {
            throw new IllegalArgumentException("taskPlanId and planStepId must be provided together");
        }
        if (taskPlanId != null && (tenantId == null || projectId == null)) {
            throw new IllegalArgumentException(
                    "canonical Task execution requires tenantId, projectId and taskId");
        }
        if (tenantId != null && projectId != null && taskPlanId == null) {
            throw new IllegalArgumentException(
                    "canonical Task execution requires taskPlanId and planStepId");
        }
        if ((projectDirectoryId == null) != (workspaceId == null)) {
            throw new IllegalArgumentException(
                    "projectDirectoryId and workspaceId must be provided together");
        }
        if (workspaceId != null && (tenantId == null || projectId == null || taskId == null)) {
            throw new IllegalArgumentException(
                    "Workspace-bound execution requires canonical Project Task scope");
        }
    }

    public StartAgentRunCommand(
            String tenantId, String ownerId, String agentId, String configurationSnapshotId,
            String conversationId, String chatTaskId, String projectId,
            String projectDirectoryId, String workspaceId, String taskId,
            String taskPlanId, String planStepId) {
        this(tenantId, ownerId, agentId, configurationSnapshotId, conversationId, chatTaskId,
                projectId, projectDirectoryId, workspaceId, taskId, taskPlanId,
                planStepId, null);
    }

    /** Compatibility constructor before standalone Chat Task pinning. */
    public StartAgentRunCommand(
            String tenantId, String ownerId, String agentId, String configurationSnapshotId,
            String conversationId, String projectId, String projectDirectoryId,
            String workspaceId, String taskId, String taskPlanId, String planStepId) {
        this(tenantId, ownerId, agentId, configurationSnapshotId, conversationId, null,
                projectId, projectDirectoryId, workspaceId, taskId, taskPlanId, planStepId, null);
    }

    /** Compatibility constructor before ProjectDirectory/Workspace run binding. */
    public StartAgentRunCommand(
            String tenantId, String ownerId, String agentId, String configurationSnapshotId,
            String conversationId, String projectId, String taskId,
            String taskPlanId, String planStepId) {
        this(tenantId, ownerId, agentId, configurationSnapshotId, conversationId, null, projectId,
                null, null, taskId, taskPlanId, planStepId, null);
    }

    /** Compatibility constructor for Chat and the pre-M18 Python adapter. */
    public StartAgentRunCommand(
            String ownerId,
            String agentId,
            String configurationSnapshotId,
            String conversationId,
            String projectId,
            String taskId) {
        this(null, ownerId, agentId, configurationSnapshotId, conversationId, null,
                projectId, null, null, taskId, null, null, null);
    }

    public boolean taskScoped() {
        return tenantId != null && projectId != null && taskId != null
                && taskPlanId != null && planStepId != null;
    }

    public boolean workspaceScoped() {
        return projectDirectoryId != null && workspaceId != null;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
