package com.spaceagent.platform.project.api;

public record TransitionChatTaskCommand(
        String tenantId,
        String userId,
        String conversationId,
        String taskId,
        TaskTransition transition) {
}
