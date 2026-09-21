package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.TaskPlanStatus;

import java.time.Instant;
import java.util.List;

public record TaskPlanView(
        String id,
        String projectId,
        String rootTaskId,
        int versionNumber,
        TaskPlanStatus status,
        String generatedByConfigurationHash,
        String createdBy,
        String approvedBy,
        Instant approvedAt,
        List<PlanStepView> steps,
        Instant createdAt,
        Instant updatedAt,
        String scope,
        String conversationId,
        String sourceAgentRunId,
        String proposalHash,
        String strategySummary,
        String generatedByAgentId,
        String generatedByRunConfigurationSnapshotId) {

    public TaskPlanView {
        steps = List.copyOf(steps);
    }

    public TaskPlanView(
            String id, String projectId, String rootTaskId, int versionNumber,
            TaskPlanStatus status, String generatedByConfigurationHash, String createdBy,
            String approvedBy, Instant approvedAt, List<PlanStepView> steps,
            Instant createdAt, Instant updatedAt) {
        this(id, projectId, rootTaskId, versionNumber, status, generatedByConfigurationHash,
                createdBy, approvedBy, approvedAt, steps, createdAt, updatedAt,
                projectId == null ? "CHAT" : "PROJECT", null, null, null, null,
                null, null);
    }

    public TaskPlanView(
            String id, String projectId, String rootTaskId, int versionNumber,
            TaskPlanStatus status, String generatedByConfigurationHash, String createdBy,
            String approvedBy, Instant approvedAt, List<PlanStepView> steps,
            Instant createdAt, Instant updatedAt, String scope, String conversationId,
            String sourceAgentRunId, String proposalHash, String strategySummary) {
        this(id, projectId, rootTaskId, versionNumber, status, generatedByConfigurationHash,
                createdBy, approvedBy, approvedAt, steps, createdAt, updatedAt, scope,
                conversationId, sourceAgentRunId, proposalHash, strategySummary, null, null);
    }
}
