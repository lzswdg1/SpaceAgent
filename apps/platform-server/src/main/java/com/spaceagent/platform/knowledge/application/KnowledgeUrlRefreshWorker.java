package com.spaceagent.platform.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@ConditionalOnProperty(
        prefix = "platform.knowledge.url-refresh",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KnowledgeUrlRefreshWorker {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeUrlRefreshWorker.class);

    private final KnowledgeUrlRefreshCoordinator coordinator;
    private final String worker = "knowledge-url:" + UUID.randomUUID();
    private final int leaseSeconds;

    public KnowledgeUrlRefreshWorker(
            KnowledgeUrlRefreshCoordinator coordinator,
            @Value("${platform.knowledge.url-refresh.lease-seconds:300}") int leaseSeconds) {
        this.coordinator = coordinator;
        this.leaseSeconds = Math.max(60, Math.min(leaseSeconds, 300));
    }

    @Scheduled(fixedDelayString = "${platform.knowledge.url-refresh.poll-delay-ms:1000}")
    public void poll() {
        try {
            for (int index = 0; index < 10 && coordinator.runOnce(worker, leaseSeconds); index++) {
                // Drain a bounded batch.
            }
        } catch (RuntimeException error) {
            LOG.warn("Knowledge URL refresh failed safely: type={}", error.getClass().getSimpleName());
        }
    }
}
