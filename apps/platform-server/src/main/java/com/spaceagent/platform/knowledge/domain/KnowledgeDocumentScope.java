package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;

/** Additive ownership mapping: legacy document IDs never become knowledge base IDs. */
public record KnowledgeDocumentScope(String documentId, String baseId, String originalOwnerId,
                                     String provenance, Instant assignedAt) {}
