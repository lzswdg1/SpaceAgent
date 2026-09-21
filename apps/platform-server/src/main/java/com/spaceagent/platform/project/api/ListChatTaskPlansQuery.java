package com.spaceagent.platform.project.api;

public record ListChatTaskPlansQuery(
        String tenantId,
        String userId,
        String conversationId,
        String rootTaskId) {
}
