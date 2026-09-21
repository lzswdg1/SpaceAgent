package com.spaceagent.platform.project.api;

public record ResolveChatPlanStepExecutionQuery(
        String tenantId, String userId, String conversationId, String rootTaskId,
        String taskId, String taskPlanId, String planStepId) {
}
