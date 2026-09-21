package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.List;

/**
 * A retrievable chunk of a {@link KnowledgeDocument}.
 */
public record KnowledgeChunk(
        String id,
        String documentId,
        int sequence,
        String content,
        String embeddingReference,
        String contentHash,
        String embeddingModel,
        int embeddingDimensions,
        List<Double> embedding,
        Instant createdAt) {

    public KnowledgeChunk {
        embedding = embedding == null ? List.of() : List.copyOf(embedding);
    }

    public KnowledgeChunk(
            String id,
            String documentId,
            int sequence,
            String content,
            String embeddingReference,
            Instant createdAt) {
        this(id, documentId, sequence, content, embeddingReference,
                null, null, 0, List.of(), createdAt);
    }
}
