package com.spaceagent.platform.inference.application;

import com.spaceagent.platform.inference.api.ProviderHealthProbeApplicationApi;
import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "platform.inference", name = "health-probes-enabled",
        havingValue = "true", matchIfMissing = true)
public class ProviderHealthProbeWorker {
    private final ProviderHealthProbeApplicationApi probes;
    private final InferenceProperties properties;
    private final String workerId;

    public ProviderHealthProbeWorker(
            ProviderHealthProbeApplicationApi probes, InferenceProperties properties) {
        this.probes = probes;
        this.properties = properties;
        this.workerId = properties.getHealthProbeWorkerId() == null
                || properties.getHealthProbeWorkerId().isBlank()
                ? "provider-probe:" + UUID.randomUUID()
                : properties.getHealthProbeWorkerId().trim();
    }

    @Scheduled(fixedDelayString =
            "${platform.inference.health-probe-poll-delay-ms:1000}")
    public void poll() {
        for (int index = 0; index < properties.getHealthProbeBatchSize(); index++) {
            if (!probes.runOnce(workerId)) return;
        }
    }
}
