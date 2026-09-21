package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.api.McpRegistryAdministrationApi;
import com.spaceagent.platform.tooling.infrastructure.McpToolingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "platform.tooling.mcp.registry", name = "worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class McpRegistrySynchronizationWorker {
    private final McpRegistryAdministrationApi registry;
    private final String workerId;

    public McpRegistrySynchronizationWorker(
            McpRegistryAdministrationApi registry, McpToolingProperties properties) {
        this.registry = registry;
        String configured = properties.getRegistry().getWorkerId();
        this.workerId = configured == null || configured.isBlank()
                ? "mcp-registry:" + UUID.randomUUID() : configured.trim();
    }

    @Scheduled(fixedDelayString = "${platform.tooling.mcp.registry.poll-delay-ms:1000}")
    public void poll() {
        registry.runOnce(workerId);
    }
}
