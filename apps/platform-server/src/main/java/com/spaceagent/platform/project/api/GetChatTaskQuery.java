package com.spaceagent.platform.project.api;

public record GetChatTaskQuery(
        String tenantId,
        String userId,
        String conversationId,
        String taskId) {
}
