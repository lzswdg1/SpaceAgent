package com.spaceagent.platform.integration.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconciles restart-safe PAUSING/CANCELLING Project execution transitions. */
@Component
@ConditionalOnProperty(
        prefix = "platform.project.execution-control",
        name = "recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ProjectPlanExecutionRecoveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectPlanExecutionRecoveryWorker.class);

    private final ProjectCodingCoordinator coordinator;

    public ProjectPlanExecutionRecoveryWorker(ProjectCodingCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString =
            "${platform.project.execution-control.recovery-delay-ms:1000}")
    public void poll() {
        try {
            coordinator.recoverControlTransitions(50);
        } catch (RuntimeException error) {
            LOG.warn("Project execution control recovery failed safely: type={}",
                    error.getClass().getSimpleName());
        }
    }
}
