package com.spaceagent.platform.inference.domain;

import java.time.Instant;

/** One configured ProviderModel candidate inside a ModelPool. */
public record ModelPoolMember(
        String id,
        String poolId,
        String providerId,
        String providerModelId,
        int priority,
        int weight,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}
