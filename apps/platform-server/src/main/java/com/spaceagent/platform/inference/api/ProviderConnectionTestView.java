package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.inference.domain.ProviderConnectionStatus;

import java.time.Instant;
import java.util.List;

public record ProviderConnectionTestView(
        String providerId,
        boolean success,
        ProviderConnectionStatus status,
        int latencyMs,
        List<String> discoveredModelIds,
        String errorCode,
        Instant testedAt) {

    public ProviderConnectionTestView {
        discoveredModelIds = List.copyOf(discoveredModelIds);
    }
}
