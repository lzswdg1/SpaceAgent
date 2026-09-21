package com.spaceagent.platform.conversation.api;

import java.time.Instant;

/**
 * Public conversation-context snapshot query result.
 */
public record ConversationContextSnapshotView(
        String id,
        String conversationId,
        int version,
        String summary,
        int fromMessageSequence,
        int toMessageSequence,
        int tokenCount,
        String checksum,
        Instant createdAt) {
}
