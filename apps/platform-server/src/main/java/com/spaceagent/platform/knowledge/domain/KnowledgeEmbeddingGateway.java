package com.spaceagent.platform.knowledge.domain;

import java.util.List;

/**
 * Provider-neutral embedding request boundary.
 */
public interface KnowledgeEmbeddingGateway {

    EmbeddingBatch embed(List<String> inputs);

    record EmbeddingBatch(String model, List<List<Double>> vectors) {
        public EmbeddingBatch {
            vectors = vectors == null ? List.of() : vectors.stream().map(List::copyOf).toList();
        }
    }
}
