package com.spaceagent.platform.conversation.api;

import java.time.Instant;

/**
 * Public message query result.
 */
public record MessageView(
        String id,
        String conversationId,
        int sequence,
        String role,
        String content,
        Instant createdAt) {
}
