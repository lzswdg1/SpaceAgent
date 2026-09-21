package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;

/**
 * Public command for advancing or failing a knowledge document ingestion state.
 */
public record MarkKnowledgeDocumentStatusCommand(
        String documentId,
        KnowledgeDocumentStatus status,
        String errorReason) {

    public MarkKnowledgeDocumentStatusCommand {
        requireNonBlank(documentId, "documentId");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
