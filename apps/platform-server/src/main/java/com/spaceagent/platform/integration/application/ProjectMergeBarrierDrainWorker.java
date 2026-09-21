package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.integration.infrastructure.ProjectCodingProperties;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${platform.project.coding.worker-enabled:true}' == 'true' and '${platform.sandbox.mode:in-process}' == 'http'")
public class ProjectMergeBarrierDrainWorker {
    private final ProjectCodingCoordinator coordinator;
    private final ProjectCodingProperties properties;
    private final String workerId;

    public ProjectMergeBarrierDrainWorker(
            ProjectCodingCoordinator coordinator, ProjectCodingProperties properties) {
        this.coordinator = coordinator;
        this.properties = properties;
        this.workerId = "project-merge-barrier:" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${platform.project.coding.poll-delay-ms:1000}")
    public void poll() {
        coordinator.drainMergeBarrierOnce(workerId, properties.getLeaseSeconds(),
                properties.getMaximumAttempts());
    }
}
