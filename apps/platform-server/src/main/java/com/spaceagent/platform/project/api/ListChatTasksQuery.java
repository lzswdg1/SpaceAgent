package com.spaceagent.platform.project.api;

public record ListChatTasksQuery(
        String tenantId,
        String userId,
        String conversationId,
        int limit) {
}
