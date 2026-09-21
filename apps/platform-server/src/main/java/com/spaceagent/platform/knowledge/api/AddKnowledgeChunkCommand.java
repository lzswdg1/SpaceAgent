package com.spaceagent.platform.knowledge.api;

/**
 * Public command for appending a chunk to a knowledge document.
 */
public record AddKnowledgeChunkCommand(
        String documentId,
        int sequence,
        String content,
        String embeddingReference) {

    public AddKnowledgeChunkCommand {
        requireNonBlank(documentId, "documentId");
        requireNonBlank(content, "content");
        if (sequence < 0 || sequence >= 512) {
            throw new IllegalArgumentException("sequence must be between 0 and 511");
        }
        if (content.length() > 20_000) {
            throw new IllegalArgumentException("content must not exceed 20000 characters");
        }
        if (embeddingReference != null && embeddingReference.length() > 1_000) {
            throw new IllegalArgumentException("embeddingReference must not exceed 1000 characters");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
