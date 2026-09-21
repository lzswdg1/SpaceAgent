package com.spaceagent.platform.project.api;

public record ResolveChatTaskExecutionQuery(
        String tenantId,
        String userId,
        String conversationId,
        String taskId) {
}
