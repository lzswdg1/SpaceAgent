package com.spaceagent.platform.inference.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProviderHealthProbeRepository {
    void synchronize(ModelProvider provider, Instant now);

    Optional<ProviderHealthProbeClaim> claimDue(String owner, long leaseSeconds);

    boolean complete(Completion completion);

    void recordManual(ManualObservation observation);

    List<ProviderHealthObservation> findObservations(String tenantId, String providerId, int limit);

    record Completion(
            ProviderHealthProbeClaim claim,
            String observationId,
            ProviderConnectionProbeResult result,
            Instant observedAt,
            long successIntervalSeconds,
            long retryDelaySeconds) {
    }

    record ManualObservation(
            ModelProvider provider,
            String observationId,
            ProviderConnectionProbeResult result,
            Instant observedAt,
            long nextProbeSeconds) {
    }
}
