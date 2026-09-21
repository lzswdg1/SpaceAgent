package com.spaceagent.platform.project.api;

public record GetChatTaskPlanQuery(
        String tenantId,
        String userId,
        String conversationId,
        String rootTaskId,
        String taskPlanId) {
}
