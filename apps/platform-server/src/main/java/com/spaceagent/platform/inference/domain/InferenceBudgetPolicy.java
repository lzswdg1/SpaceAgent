package com.spaceagent.platform.inference.domain;

import java.time.Instant;

public record InferenceBudgetPolicy(String tenantId,boolean enabled,long monthlyRequestLimit,
        long monthlyTokenLimit,long monthlyCostLimitMicros,long revision,String updatedBy,
        Instant createdAt,Instant updatedAt){}
