package com.spaceagent.platform.project.api;

public record TransitionChatPlanStepExecutionCommand(
        String tenantId, String userId, String conversationId, String rootTaskId,
        String taskId, String taskPlanId, String planStepId, PlanStepExecutionAction action) {
    public TransitionChatPlanStepExecutionCommand {
        if (action == null) throw new IllegalArgumentException("action is required");
    }
}
