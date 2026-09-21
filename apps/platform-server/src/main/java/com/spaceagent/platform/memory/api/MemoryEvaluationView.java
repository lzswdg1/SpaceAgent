package com.spaceagent.platform.memory.api;

public record MemoryEvaluationView(
        boolean candidateCreated,
        MemoryCandidateView candidate) {

    public static MemoryEvaluationView skipped() {
        return new MemoryEvaluationView(false, null);
    }
}
