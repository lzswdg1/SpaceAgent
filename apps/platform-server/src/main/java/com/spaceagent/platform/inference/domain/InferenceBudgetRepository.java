package com.spaceagent.platform.inference.domain;

import java.util.Optional;

public interface InferenceBudgetRepository {
    InferenceBudgetPolicy savePolicy(InferenceBudgetPolicy policy);
    Optional<InferenceBudgetPolicy> findPolicy(String tenantId);
    ReservationDecision reserve(ReserveRequest request);
    Optional<InferenceBudgetReservation> settle(String id,long inputTokens,long outputTokens);
    void release(String id);
    void markUnknown(String id);
    BudgetUsage usage(String tenantId);
    Optional<InferenceBudgetReservation> findEmbeddingReservation(String embeddingCallId);

    record ReserveRequest(String id,String tenantId,String agentRunId,String logicalCallId,
            String providerId,String modelId,String priceId,Long inputRate,Long outputRate,
            long reservedTokens,Long reservedCostMicros,String embeddingCallId){
        public ReserveRequest(String id,String tenantId,String agentRunId,String logicalCallId,String providerId,String modelId,
                String priceId,Long inputRate,Long outputRate,long reservedTokens,Long reservedCostMicros) {
            this(id,tenantId,agentRunId,logicalCallId,providerId,modelId,priceId,inputRate,outputRate,reservedTokens,reservedCostMicros,null);
        }
        public ReserveRequest {
            if((agentRunId==null)==(embeddingCallId==null)) throw new IllegalArgumentException("Exactly one inference budget subject is required");
        }
    }
    record ReservationDecision(InferenceBudgetReservation reservation,boolean created){}
    record BudgetUsage(long consumedRequests,long reservedRequests,long consumedTokens,
            long reservedTokens,long consumedCostMicros,long reservedCostMicros){}
}
