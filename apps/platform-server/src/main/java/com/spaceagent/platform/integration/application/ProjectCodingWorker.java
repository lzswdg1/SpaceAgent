package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.integration.infrastructure.ProjectCodingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnExpression("'${platform.project.coding.worker-enabled:true}' == 'true' and '${platform.sandbox.mode:in-process}' == 'http'")
public class ProjectCodingWorker {
    private final ProjectCodingCoordinator coordinator; private final ProjectCodingProperties properties;
    private final String workerId;
    public ProjectCodingWorker(ProjectCodingCoordinator coordinator,ProjectCodingProperties properties){this.coordinator=coordinator;this.properties=properties;this.workerId=properties.getWorkerId()==null||properties.getWorkerId().isBlank()?"project-coding:"+UUID.randomUUID():properties.getWorkerId().trim();}
    @Scheduled(fixedDelayString="${platform.project.coding.poll-delay-ms:1000}") public void poll(){coordinator.runOnce(workerId,properties.getLeaseSeconds(),properties.getMaximumAttempts(),properties.getMaximumIterations(),properties.getMaximumReviewRounds());}
}
