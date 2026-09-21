package com.spaceagent.platform.conversation.domain;

import java.time.Instant;

/**
 * One durable message inside a {@link Conversation}.
 */
public record Message(
        String id,
        String conversationId,
        int sequence,
        String role,
        String content,
        Instant createdAt) {

    public Message {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (content == null) {
            content = "";
        }
    }
}
