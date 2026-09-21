package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable identity of one model vector space, not merely a dimensionality. */
public record KnowledgeEmbeddingSpace(String id, String baseId, String providerId, String modelId,
        String modelRevision, String providerFingerprint, int dimensions, String preprocessingHash,
        String fingerprint, String createdBy, Instant createdAt) {
    public KnowledgeEmbeddingSpace {
        for (String value : List.of(id, baseId, providerId, modelId, modelRevision, createdBy)) {
            if (value.isBlank()) throw new IllegalArgumentException("Embedding space identity is required");
        }
        if (dimensions < 1 || dimensions > 32768) throw new IllegalArgumentException("Invalid embedding dimensions");
        for (String hash : List.of(providerFingerprint, preprocessingHash, fingerprint)) {
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid embedding space hash");
        }
        Objects.requireNonNull(createdAt);
    }

    public void requireCompatible(KnowledgeEmbeddingSpace other) {
        if (!id.equals(other.id()) || !fingerprint.equals(other.fingerprint())) {
            throw new IllegalArgumentException("Embedding spaces differ even when dimensions match");
        }
    }

    public void validateVector(List<Double> vector) {
        if (vector == null || vector.size() != dimensions || vector.stream().anyMatch(v -> v == null || !Double.isFinite(v))
                || vector.stream().allMatch(v -> v == 0.0)) throw new IllegalArgumentException("Invalid cosine vector");
    }
}
