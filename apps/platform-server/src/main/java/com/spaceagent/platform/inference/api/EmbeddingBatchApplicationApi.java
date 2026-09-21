package com.spaceagent.platform.inference.api;

import java.util.List;

/** Trusted internal API. Callers authorize their resource and actor; Inference authorizes the Provider. */
public interface EmbeddingBatchApplicationApi {
    Result embed(Request request);
    static ModelCapabilities modelCapabilities(String modelId) {
        var capabilities = com.spaceagent.platform.inference.domain.EmbeddingModelCapabilities.forModel(modelId);
        return new ModelCapabilities(capabilities.maximumItems(), capabilities.dimensionsParameterSupported());
    }
    record ModelCapabilities(int maximumItems, boolean dimensionsParameterSupported) {}
    /** Recovers cached evidence or fences an unstarted dispatch; must never execute a Provider request. */
    default Result reconcile(Request request) {return new Result(null,Status.UNKNOWN,List.of(),null,null,"EMBEDDING_RECONCILIATION_UNAVAILABLE");}
    default boolean eraseJobCache(String tenantId,String actorId,String jobId) {return false;}
    static int conservativeInputTokens(String input) {
        return com.spaceagent.platform.inference.domain.EmbeddingBounds.inputUpperBound(input);
    }
    record Request(String tenantId,String actorId,String operationKey,String providerId,String modelId,
                   String providerFingerprint,String spaceFingerprint,int dimensions,List<String> inputs) {
        public Request { inputs=inputs==null?List.of():List.copyOf(inputs); }
        @Override public String toString() { return "EmbeddingRequest[content redacted]"; }
    }
    enum Status { SUCCEEDED, REJECTED, UNKNOWN, IN_PROGRESS }
    record Result(String callId,Status status,List<List<Double>> vectors,Long inputTokens,Long costMicros,String safeCode) {
        public Result { vectors=vectors.stream().map(List::copyOf).toList(); }
        @Override public String toString() { return "EmbeddingResult["+status+", vectors redacted]"; }
    }
}
