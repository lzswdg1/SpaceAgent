package com.spaceagent.platform.project.api;

import java.util.List;

public record CreateTaskPlanCommand(
        String tenantId,
        String userId,
        String projectId,
        String rootTaskId,
        String generatedByConfigurationHash,
        List<PlanStepDraft> steps,
        String generatedByAgentId,
        String generatedByRunConfigurationSnapshotId) {

    public CreateTaskPlanCommand(
            String tenantId, String userId, String projectId, String rootTaskId,
            String generatedByConfigurationHash, List<PlanStepDraft> steps) {
        this(tenantId, userId, projectId, rootTaskId, generatedByConfigurationHash,
                steps, null, null);
    }

    public CreateTaskPlanCommand {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record PlanStepDraft(
            String stepKey,
            String childTaskId,
            List<String> dependsOnStepKeys,
            String requiredCapability,
            String preferredAgentId,
            String expectedOutput,
            List<String> acceptanceCriteria,
            boolean approvalRequired) {

        public PlanStepDraft {
            dependsOnStepKeys = dependsOnStepKeys == null
                    ? List.of() : List.copyOf(dependsOnStepKeys);
            acceptanceCriteria = acceptanceCriteria == null
                    ? List.of() : List.copyOf(acceptanceCriteria);
        }
    }
}
