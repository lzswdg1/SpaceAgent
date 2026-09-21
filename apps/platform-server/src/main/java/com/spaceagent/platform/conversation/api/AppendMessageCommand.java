package com.spaceagent.platform.conversation.api;

/**
 * Public command for appending a message to a conversation.
 */
public record AppendMessageCommand(
        String conversationId,
        int sequence,
        String role,
        String content) {

    public AppendMessageCommand {
        requireNonBlank(conversationId, "conversationId");
        requireNonBlank(role, "role");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        if (content == null) {
            content = "";
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
