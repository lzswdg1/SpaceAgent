package com.spaceagent.platform.inference.domain;

import java.time.Instant;

public record InferenceBudgetReservation(String id,String tenantId,String agentRunId,
        String logicalCallId,String providerId,String modelId,String priceId,
        Long inputPriceMicrosPerMillion,Long outputPriceMicrosPerMillion,
        long reservedTokens,Long reservedCostMicros,String status,
        Long actualInputTokens,Long actualOutputTokens,Long actualCostMicros,
        Instant createdAt,Instant updatedAt){}
