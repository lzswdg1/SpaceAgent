package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignmentSource;

import java.time.Instant;

/** Public Runtime contract for durable per-PlanStep Agent assignment evidence. */
public interface ProjectPlanStepAssignmentApplicationApi {
    AssignmentView definePlanDefault(PlanDefaultAssignmentCommand command);

    AssignmentView defineStepOverride(StepOverrideAssignmentCommand command);

    AssignmentView handoff(HandoffAssignmentCommand command);

    AssignmentView get(AssignmentQuery query);

    record PlanDefaultAssignmentCommand(
            String tenantId,
            String ownerId,
            String projectId,
            String taskPlanId,
            String planStepId,
            String agentId,
            String primaryConfigurationHash,
            String reviewerAgentId,
            String reviewerConfigurationHash,
            String modelPoolId,
            String capabilityHash,
            String configurationHash) {}

    record StepOverrideAssignmentCommand(
            String tenantId,
            String ownerId,
            String projectId,
            String taskPlanId,
            String planStepId,
            String agentId,
            String primaryConfigurationHash,
            String reviewerAgentId,
            String reviewerConfigurationHash,
            String modelPoolId,
            String capabilityHash,
            String configurationHash) {}

    record HandoffAssignmentCommand(
            String tenantId, String ownerId, String projectId, String taskPlanId, String planStepId,
            String agentId, String primaryConfigurationHash, String reviewerAgentId,
            String reviewerConfigurationHash, String modelPoolId, String capabilityHash,
            String configurationHash) {}

    record AssignmentQuery(
            String tenantId,
            String ownerId,
            String projectId,
            String taskPlanId,
            String planStepId) {}

    record AssignmentView(
            String id,
            String taskPlanId,
            String planStepId,
            long revision,
            ProjectPlanStepAssignmentSource source,
            String agentId,
            String primaryConfigurationHash,
            String reviewerAgentId,
            String reviewerConfigurationHash,
            String modelPoolId,
            String capabilityHash,
            String configurationHash,
            String assignmentHash,
            Instant assignedAt) {}
}
