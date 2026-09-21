package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.integration.infrastructure.ProjectCodingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnExpression("'${platform.project.coding.worker-enabled:true}' == 'true' and '${platform.sandbox.mode:in-process}' == 'http'")
public class ProjectHandoffFinalizerWorker {
    private final ProjectHandoffFinalizer finalizer;
    private final ProjectCodingProperties properties;
    private final String workerId;

    public ProjectHandoffFinalizerWorker(
            ProjectHandoffFinalizer finalizer, ProjectCodingProperties properties) {
        this.finalizer = finalizer;
        this.properties = properties;
        this.workerId = properties.getWorkerId() == null || properties.getWorkerId().isBlank()
                ? "project-handoff-finalizer:" + UUID.randomUUID()
                : properties.getWorkerId().trim() + ":handoff-finalizer";
    }

    @Scheduled(fixedDelayString = "${platform.project.coding.poll-delay-ms:1000}")
    public void poll() {
        finalizer.runOnce(workerId, properties.getLeaseSeconds(), properties.getMaximumAttempts());
    }
}
