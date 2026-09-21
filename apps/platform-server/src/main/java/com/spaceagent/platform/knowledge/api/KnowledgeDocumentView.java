package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;

import java.time.Instant;

/**
 * Public knowledge-document query result.
 */
public record KnowledgeDocumentView(
        String id,
        String ownerId,
        String name,
        String contentType,
        String storageLocation,
        KnowledgeDocumentStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
