package com.spaceagent.platform.inference.api;

import java.time.Instant;

public interface InferenceBudgetApplicationApi {
    PolicyView configure(ConfigurePolicyCommand command);
    PolicyView policy(String tenantId);
    UsageView usage(String tenantId);
    ReservationView reserve(ReserveCommand command);
    void settle(String reservationId,long inputTokens,long outputTokens);
    void release(String reservationId);
    void markUnknown(String reservationId);

    record ConfigurePolicyCommand(String tenantId,String userId,boolean enabled,
            long monthlyRequestLimit,long monthlyTokenLimit,long monthlyCostLimitMicros){}
    record ReserveCommand(String tenantId,String agentRunId,String logicalCallId,
            String providerId,String modelId,String priceId,Long inputRate,Long outputRate,
            long estimatedInputTokens,long maxOutputTokens){}
    record PolicyView(String tenantId,boolean enabled,long monthlyRequestLimit,
            long monthlyTokenLimit,long monthlyCostLimitMicros,long revision,Instant updatedAt){}
    record UsageView(long consumedRequests,long reservedRequests,long consumedTokens,
            long reservedTokens,long consumedCostMicros,long reservedCostMicros){}
    record ReservationView(String id,String status,boolean created,Long reservedCostMicros){}
}
