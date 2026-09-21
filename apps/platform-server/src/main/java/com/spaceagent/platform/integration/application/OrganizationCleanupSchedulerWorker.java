package com.spaceagent.platform.integration.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@ConditionalOnProperty(prefix = "platform.identity.cleanup", name = "worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class OrganizationCleanupSchedulerWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OrganizationCleanupSchedulerWorker.class);
    private final OrganizationCleanupExecutionCoordinator coordinator;
    private final String workerId;
    private final int leaseSeconds;

    public OrganizationCleanupSchedulerWorker(
            OrganizationCleanupExecutionCoordinator coordinator,
            @Value("${platform.identity.cleanup.worker-id:cleanup-${random.uuid}}") String workerId,
            @Value("${platform.identity.cleanup.lease-seconds:60}") int leaseSeconds) {
        this.coordinator = coordinator; this.workerId = workerId; this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${platform.identity.cleanup.poll-delay-ms:1000}")
    public void poll() {
        try {
            coordinator.runOnce(workerId, leaseSeconds);
        } catch (RuntimeException failure) {
            LOG.warn("Organization cleanup poll failed safely: type={}",
                    failure.getClass().getSimpleName());
        }
    }
}
