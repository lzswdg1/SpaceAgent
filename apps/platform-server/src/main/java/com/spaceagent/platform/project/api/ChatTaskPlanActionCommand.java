package com.spaceagent.platform.project.api;

public record ChatTaskPlanActionCommand(
        String tenantId,
        String userId,
        String conversationId,
        String rootTaskId,
        String taskPlanId,
        TaskPlanAction action) {
}
