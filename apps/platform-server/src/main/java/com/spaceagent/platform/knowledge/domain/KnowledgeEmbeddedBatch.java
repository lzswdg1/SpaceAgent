package com.spaceagent.platform.knowledge.domain;

import java.util.List;

/** Reusable vector artifact. Plain chunk text remains in the Knowledge source/chunk store. */
public record KnowledgeEmbeddedBatch(String spaceId,String spaceFingerprint,List<ChunkVector> chunks) {
    public KnowledgeEmbeddedBatch { chunks=List.copyOf(chunks); }
    public record ChunkVector(String chunkId,String contentHash,List<Double> vector) {
        public ChunkVector { vector=List.copyOf(vector); }
    }
    @Override public String toString() { return "KnowledgeEmbeddedBatch[vectors redacted]"; }
}
