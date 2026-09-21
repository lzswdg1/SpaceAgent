package com.spaceagent.platform.conversation.api;

/**
 * Public command for deleting a conversation and its messages.
 */
public record DeleteConversationCommand(String conversationId) {

    public DeleteConversationCommand {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
    }
}
