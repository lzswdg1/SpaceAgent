package com.spaceagent.platform.inference.domain;

import java.util.List;

/** Secret-safe infrastructure result from testing one Provider endpoint. */
public record ProviderConnectionProbeResult(
        boolean success,
        int latencyMs,
        List<String> discoveredModelIds,
        String errorCode) {

    public ProviderConnectionProbeResult {
        discoveredModelIds = discoveredModelIds == null
                ? List.of()
                : List.copyOf(discoveredModelIds);
    }
}
