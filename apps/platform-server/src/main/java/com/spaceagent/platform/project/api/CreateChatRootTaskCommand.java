package com.spaceagent.platform.project.api;

public record CreateChatRootTaskCommand(
        String tenantId,
        String userId,
        String conversationId,
        String sourceMessageId,
        String title,
        String goal) {
}
