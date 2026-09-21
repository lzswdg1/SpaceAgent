package com.spaceagent.platform.knowledge.api;

import java.util.List;

/**
 * Owner-scoped similarity retrieval. documentIds are Agent-held references, not
 * Agent or Conversation state owned by Knowledge.
 */
public record KnowledgeRetrievalCommand(
        String ownerId,
        List<String> documentIds,
        String query,
        int topK) {

    public KnowledgeRetrievalCommand {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (query.length() > 8_000) {
            throw new IllegalArgumentException("query must not exceed 8000 characters");
        }
        documentIds = documentIds == null ? List.of() : documentIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (documentIds.size() > 64) {
            throw new IllegalArgumentException("documentIds must not contain more than 64 values");
        }
        if (documentIds.stream().anyMatch(id -> id.length() > 64)) {
            throw new IllegalArgumentException("documentId must not exceed 64 characters");
        }
        topK = topK <= 0 ? 5 : Math.min(topK, 100);
    }
}
