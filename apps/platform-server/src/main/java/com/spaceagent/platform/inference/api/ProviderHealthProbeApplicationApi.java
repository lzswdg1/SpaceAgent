package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.inference.domain.ProviderConnectionStatus;

import java.time.Instant;
import java.util.List;

public interface ProviderHealthProbeApplicationApi {
    boolean runOnce(String workerId);

    List<ObservationView> observations(String tenantId, String providerId, int limit);

    record ObservationView(
            String id,
            String providerId,
            boolean success,
            ProviderConnectionStatus status,
            int latencyMs,
            List<String> discoveredModelIds,
            String errorCode,
            String source,
            Instant observedAt) {
    }
}
