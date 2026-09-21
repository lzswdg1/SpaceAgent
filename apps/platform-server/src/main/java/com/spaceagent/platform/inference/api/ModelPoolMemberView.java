package com.spaceagent.platform.inference.api;

import java.time.Instant;

public record ModelPoolMemberView(
        String id,
        String poolId,
        String providerId,
        String providerModelId,
        String modelId,
        int priority,
        int weight,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}
