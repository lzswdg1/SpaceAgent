package com.spaceagent.platform.conversation.api;

/** Participant-scoped focus change; null taskId clears the active Task. */
public record SetConversationActiveTaskCommand(
        String tenantId,
        String userId,
        String conversationId,
        String taskId) {
}
