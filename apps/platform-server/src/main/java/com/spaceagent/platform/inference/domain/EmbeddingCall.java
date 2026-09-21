package com.spaceagent.platform.inference.domain;

import java.time.Instant;

/** Inference-owned evidence; payload and Provider credentials never appear in this record. */
public record EmbeddingCall(String id,String tenantId,String actorId,String operationKey,String requestHash,
                            String providerId,String modelId,int dimensions,String priceId,Long inputRate,
                            State state,String encryptedResponse,Long inputTokens,Long costMicros,String safeCode,
                            Instant deadline,Instant createdAt) {
    public enum State { PREPARED, DISPATCHED, SUCCEEDED, REJECTED, UNKNOWN }
    @Override public String toString() { return "EmbeddingCall["+id+", "+state+"]"; }
}
