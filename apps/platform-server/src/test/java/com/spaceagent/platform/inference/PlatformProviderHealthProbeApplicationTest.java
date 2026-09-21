package com.spaceagent.platform.inference;

import com.spaceagent.platform.inference.api.CreateModelProviderCommand;
import com.spaceagent.platform.inference.application.*;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import com.spaceagent.platform.inference.infrastructure.memory.*;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformProviderHealthProbeApplicationTest {
    @Test
    void schedulesClaimsBacksOffAndRecoversExpiredReadOnlyProbe() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-23T18:00:00Z"));
        var providers = new InMemoryInferenceProviderRepository();
        var pools = new InMemoryModelPoolRepository();
        var probes = new InMemoryProviderHealthProbeRepository(providers, now::get);
        var ids = new UuidGenerator();
        var inference = new InferenceApplicationService(
                providers, pools, new ModelProviderSecretCipher() {
                    public String encrypt(String value) { return "enc:" + value; }
                    public String decrypt(String value) { return value.substring(4); }
                }, value -> value,
                ids, now::get, probes);
        String providerId = inference.createProvider(new CreateModelProviderCommand(
                "tenant-1", "owner-1", "scheduled", "openai-compatible",
                "https://example.com/v1", "secret", "bearer", true, false,
                List.of(new CreateModelProviderCommand.ProviderModelDraft(
                        "model-a", "Model A", 32768)))).id();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Boolean> success = new AtomicReference<>(true);
        InferenceProperties properties = new InferenceProperties();
        properties.setHealthProbeIntervalSeconds(300);
        properties.setHealthProbeRetryBaseSeconds(30);
        var service = new ProviderHealthProbeApplicationService(
                probes, providers, provider -> {
                    calls.incrementAndGet();
                    return new ProviderConnectionProbeResult(
                            success.get(), success.get() ? 12 : 9,
                            success.get() ? List.of("model-a") : List.of(),
                            success.get() ? null : "PROVIDER_AUTH_FAILED");
                }, properties, ids, now::get);

        assertThat(service.runOnce("worker-a")).isTrue();
        assertThat(service.runOnce("worker-b")).isFalse();
        assertThat(providers.findProviderById(providerId).orElseThrow().connectionStatus())
                .isEqualTo(ProviderConnectionStatus.ACTIVE);
        assertThat(service.observations("tenant-1", providerId, 10)).hasSize(1);

        now.set(now.get().plusSeconds(300));
        success.set(false);
        assertThat(service.runOnce("worker-b")).isTrue();
        assertThat(providers.findProviderById(providerId).orElseThrow().connectionStatus())
                .isEqualTo(ProviderConnectionStatus.UNHEALTHY);
        now.set(now.get().plusSeconds(30));
        ProviderHealthProbeClaim abandoned = probes.claimDue("crashed-worker", 60).orElseThrow();
        assertThat(probes.claimDue("other-worker", 60)).isEmpty();
        now.set(now.get().plusSeconds(61));
        ProviderHealthProbeClaim reclaimed = probes.claimDue("other-worker", 60).orElseThrow();
        assertThat(reclaimed.fencingToken()).isGreaterThan(abandoned.fencingToken());
        assertThat(calls).hasValue(2);
    }
}
