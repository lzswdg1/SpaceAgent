package com.spaceagent.platform.conversation.domain;

import java.time.Instant;

/**
 * A durable point-in-time snapshot of the conversation range and its compacted
 * context.
 *
 * <p>The snapshot carries enough actual compacted content and metadata for a JVM
 * restart to reconstruct the conversational context without replaying the
 * original message stream.
 */
public record ConversationContextSnapshot(
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
