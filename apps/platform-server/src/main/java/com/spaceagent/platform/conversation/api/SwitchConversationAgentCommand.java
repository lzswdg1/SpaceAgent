package com.spaceagent.platform.conversation.api;

/**
 * Changes the Agent used by future runs in an owned active Conversation.
 */
public record SwitchConversationAgentCommand(
        String tenantId,
        String userId,
        String conversationId,
        String agentId) {

    public SwitchConversationAgentCommand {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(userId, "userId");
        requireNonBlank(conversationId, "conversationId");
        requireNonBlank(agentId, "agentId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
