package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable build descriptor. Publication requires a later verified indexing job; this is not readiness. */
public record KnowledgeIndexGeneration(String id, String baseId, String documentId, String spaceId,
        String contentHash, String parserFingerprint, String chunkingFingerprint, String fingerprint,
        String createdBy, Instant createdAt) {
    public KnowledgeIndexGeneration {
        for (String value : List.of(id, baseId, documentId, spaceId, createdBy)) {
            if (value.isBlank()) throw new IllegalArgumentException("Index generation identity is required");
        }
        for (String hash : List.of(contentHash, parserFingerprint, chunkingFingerprint, fingerprint)) {
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid index generation hash");
        }
        Objects.requireNonNull(createdAt);
    }
}
