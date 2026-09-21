package com.spaceagent.platform.conversation.api;

/**
 * Public command for binding a conversation to an agent.
 */
public record BindConversationAgentCommand(String conversationId, String agentId) {

    public BindConversationAgentCommand {
        requireNonBlank(conversationId, "conversationId");
        requireNonBlank(agentId, "agentId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
