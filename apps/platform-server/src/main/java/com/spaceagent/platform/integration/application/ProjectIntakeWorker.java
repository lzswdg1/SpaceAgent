package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.project.api.ProjectIntakeApplicationApi;
import com.spaceagent.platform.integration.infrastructure.ProjectIntakeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnExpression("'${platform.project.intake.worker-enabled:true}' == 'true' "
        + "and '${platform.sandbox.mode:in-process}' == 'http'")
public class ProjectIntakeWorker {
    private final ProjectIntakeCoordinator coordinator;
    private final ProjectIntakeApplicationApi intake;
    private final ProjectIntakeProperties properties;
    private final String workerId;

    public ProjectIntakeWorker(
            ProjectIntakeCoordinator coordinator,
            ProjectIntakeApplicationApi intake,
            ProjectIntakeProperties properties) {
        this.coordinator = coordinator;
        this.intake = intake;
        this.properties = properties;
        this.workerId = properties.getWorkerId() == null || properties.getWorkerId().isBlank()
                ? "project-intake:" + UUID.randomUUID() : properties.getWorkerId().trim();
    }

    @Scheduled(fixedDelayString = "${platform.project.intake.poll-delay-ms:1000}")
    public void poll() {
        coordinator.runOnce(workerId, properties.getLeaseSeconds(), properties.getMaximumAttempts());
        intake.cleanupOneWorkspace();
    }
}
