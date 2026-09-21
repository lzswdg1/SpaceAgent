package com.spaceagent.platform.inference.domain;

import java.util.List;

public interface EmbeddingBatchExecutor {
    Outcome execute(ModelProvider provider,String modelId,int dimensions,List<String> inputs);
    enum Status { SUCCEEDED, REJECTED, UNKNOWN }
    record Outcome(Status status,List<List<Double>> vectors,Long inputTokens,String safeCode) {
        public Outcome { vectors=vectors.stream().map(List::copyOf).toList(); }
        @Override public String toString() { return "EmbeddingOutcome["+status+", vectors redacted]"; }
    }
}
