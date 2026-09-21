package com.spaceagent.platform.conversation.api;

/**
 * Public command for saving a compacted conversation context snapshot.
 */
public record SaveConversationContextSnapshotCommand(
        String conversationId,
        int version,
        String summary,
        int fromMessageSequence,
        int toMessageSequence,
        int tokenCount,
        String checksum) {

    public SaveConversationContextSnapshotCommand {
        requireNonBlank(conversationId, "conversationId");
        requireNonBlank(summary, "summary");
        requireNonBlank(checksum, "checksum");
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
        if (fromMessageSequence < 0 || toMessageSequence < fromMessageSequence) {
            throw new IllegalArgumentException("invalid message range");
        }
        if (tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must be non-negative");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
