package com.spaceagent.platform.project.domain;

public record PlanStepDependency(
        String taskPlanId,
        String stepId,
        String dependsOnStepId) {

    public PlanStepDependency {
        if (taskPlanId == null || taskPlanId.isBlank()
                || stepId == null || stepId.isBlank()
                || dependsOnStepId == null || dependsOnStepId.isBlank()) {
            throw new IllegalArgumentException("PlanStep dependency IDs are required");
        }
        if (stepId.equals(dependsOnStepId)) {
            throw new IllegalArgumentException("PlanStep cannot depend on itself");
        }
    }
}
