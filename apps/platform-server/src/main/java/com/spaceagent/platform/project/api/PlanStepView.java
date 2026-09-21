package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.PlanStepState;

import java.time.Instant;
import java.util.List;

public record PlanStepView(
        String id,
        String stepKey,
        int sequence,
        String childTaskId,
        List<String> dependencyStepIds,
        String requiredCapability,
        String preferredAgentId,
        String expectedOutput,
        List<String> acceptanceCriteria,
        boolean approvalRequired,
        PlanStepState state,
        Instant createdAt,
        Instant updatedAt) {

    public PlanStepView {
        dependencyStepIds = List.copyOf(dependencyStepIds);
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
    }
}
