package com.spaceagent.platform.inference.domain;

import java.time.Instant;
import java.util.List;

public record ProviderHealthObservation(
        String id,
        String providerId,
        String tenantId,
        String source,
        boolean success,
        ProviderConnectionStatus status,
        int latencyMs,
        List<String> discoveredModelIds,
        String errorCode,
        Instant observedAt) {
    public ProviderHealthObservation {
        discoveredModelIds = discoveredModelIds == null ? List.of() : List.copyOf(discoveredModelIds);
    }
}
