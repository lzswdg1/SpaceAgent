package com.spaceagent.platform.integration.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@ConditionalOnProperty(prefix = "platform.identity.user-cleanup", name = "worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class UserCleanupSchedulerWorker {
    private static final Logger LOG = LoggerFactory.getLogger(UserCleanupSchedulerWorker.class);
    private final UserCleanupExecutionCoordinator coordinator;
    private final String workerId;
    private final int leaseSeconds;

    public UserCleanupSchedulerWorker(
            UserCleanupExecutionCoordinator coordinator,
            @Value("${platform.identity.user-cleanup.worker-id:user-cleanup-${random.uuid}}") String workerId,
            @Value("${platform.identity.user-cleanup.lease-seconds:60}") int leaseSeconds) {
        this.coordinator = coordinator;
        this.workerId = workerId;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${platform.identity.user-cleanup.poll-delay-ms:1000}")
    public void poll() {
        try {
            coordinator.runOnce(workerId, leaseSeconds);
        } catch (RuntimeException error) {
            LOG.warn("User cleanup poll failed safely: type={}", error.getClass().getSimpleName());
        }
    }
}
