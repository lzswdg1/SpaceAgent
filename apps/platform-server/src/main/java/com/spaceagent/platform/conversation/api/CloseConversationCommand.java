package com.spaceagent.platform.conversation.api;

/**
 * Public command for closing a conversation.
 */
public record CloseConversationCommand(String conversationId) {

    public CloseConversationCommand {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
    }
}
