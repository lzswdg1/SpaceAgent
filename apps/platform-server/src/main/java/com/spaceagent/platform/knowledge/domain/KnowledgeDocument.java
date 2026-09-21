package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;

/**
 * A durable document in the knowledge module.
 * A null ownerId denotes a new organization-owned source; its explicit KnowledgeBase scope owns access and cleanup.
 */
public record KnowledgeDocument(
        String id,
        String ownerId,
        String name,
        String contentType,
        String storageLocation,
        KnowledgeDocumentStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
