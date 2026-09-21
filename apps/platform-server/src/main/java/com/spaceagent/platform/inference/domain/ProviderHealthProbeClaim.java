package com.spaceagent.platform.inference.domain;

import java.time.Instant;

public record ProviderHealthProbeClaim(
        String providerId,
        String tenantId,
        String claimToken,
        String claimOwner,
        long fencingToken,
        long revision,
        int attemptCount,
        Instant leaseUntil) {
}
