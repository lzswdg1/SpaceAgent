package com.spaceagent.platform.project.api;

/** Runtime-issued command; Project remains authoritative for PlanStep state. */
public record TransitionPlanStepExecutionCommand(
        String tenantId,
        String userId,
        String projectId,
        String taskId,
        String taskPlanId,
        String planStepId,
        PlanStepExecutionAction action) {

    public TransitionPlanStepExecutionCommand {
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        new ResolveTaskExecutionReferenceQuery(
                tenantId, userId, projectId, taskId, taskPlanId, planStepId);
    }
}
