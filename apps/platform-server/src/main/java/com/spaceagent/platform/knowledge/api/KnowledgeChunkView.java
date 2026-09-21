package com.spaceagent.platform.knowledge.api;

import java.time.Instant;
import java.util.List;

/**
 * Public knowledge-chunk query result.
 */
public record KnowledgeChunkView(
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

    public KnowledgeChunkView {
        embedding = embedding == null ? List.of() : List.copyOf(embedding);
    }

    public KnowledgeChunkView(
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
