package com.spaceagent.platform.knowledge.api;

/**
 * Requests parsing/chunking/embedding for a document. suppliedContent is optional;
 * parser adapters may resolve the durable storage reference instead.
 */
public record ProcessKnowledgeDocumentCommand(
        String documentId,
        String ownerId,
        String suppliedContent) {

    public ProcessKnowledgeDocumentCommand {
        requireNonBlank(documentId, "documentId");
        requireNonBlank(ownerId, "ownerId");
        if (suppliedContent != null && suppliedContent.length() > 1_000_000) {
            throw new IllegalArgumentException("suppliedContent must not exceed 1000000 characters");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
