package com.spaceagent.platform.inference.application;

import com.spaceagent.platform.inference.api.ProviderHealthProbeApplicationApi;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ProviderHealthProbeApplicationService implements ProviderHealthProbeApplicationApi {
    private final ProviderHealthProbeRepository probes;
    private final InferenceProviderRepository providers;
    private final ModelProviderConnectionTester tester;
    private final InferenceProperties properties;
    private final IdGenerator ids;
    private final TimeProvider time;

    public ProviderHealthProbeApplicationService(
            ProviderHealthProbeRepository probes,
            InferenceProviderRepository providers,
            ModelProviderConnectionTester tester,
            InferenceProperties properties,
            IdGenerator ids,
            TimeProvider time) {
        this.probes = probes;
        this.providers = providers;
        this.tester = tester;
        this.properties = properties;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public boolean runOnce(String workerId) {
        ProviderHealthProbeClaim claim = probes.claimDue(
                workerId, properties.getHealthProbeLeaseSeconds()).orElse(null);
        if (claim == null) return false;
        ModelProvider provider = providers.findProviderByTenantAndId(
                claim.tenantId(), claim.providerId()).orElse(null);
        if (provider == null || !provider.enabled()) return true;
        ProviderConnectionProbeResult result;
        try {
            result = tester.test(provider);
        } catch (RuntimeException error) {
            result = new ProviderConnectionProbeResult(
                    false, 0, List.of(), "PROVIDER_PROBE_FAILED");
        }
        Instant observedAt = time.now();
        long retry = Math.min(
                properties.getHealthProbeIntervalSeconds(),
                properties.getHealthProbeRetryBaseSeconds()
                        * (1L << Math.min(5, Math.max(0, claim.attemptCount() - 1))));
        probes.complete(new ProviderHealthProbeRepository.Completion(
                claim, ids.nextId(), result, observedAt,
                properties.getHealthProbeIntervalSeconds(), retry));
        return true;
    }

    @Override
    public List<ObservationView> observations(String tenantId, String providerId, int limit) {
        return probes.findObservations(tenantId, providerId, limit).stream()
                .map(value -> new ObservationView(
                        value.id(), value.providerId(), value.success(), value.status(),
                        value.latencyMs(), value.discoveredModelIds(), value.errorCode(),
                        value.source(), value.observedAt()))
                .toList();
    }
}
