package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignment;

/** Validates a new immutable assignment only through owner-module public APIs. */
public interface ProjectPlanStepAssignmentValidationApplicationApi {
    ResolvedEvidenceView resolve(ResolveEvidenceCommand command);

    ValidationView validate(ProjectPlanStepAssignment assignment);

    record ResolveEvidenceCommand(
            String tenantId,
            String ownerId,
            String agentId,
            String primaryConfigurationHash,
            String reviewerAgentId,
            String reviewerConfigurationHash,
            String modelPoolId) {}

    record ResolvedEvidenceView(
            String primaryConfigurationHash,
            String reviewerConfigurationHash,
            String modelPoolId,
            String capabilityHash,
            String configurationHash) {}

    record ValidationView(String assignmentId, String primaryConfigurationHash, String reviewerConfigurationHash,
                          String modelPoolId, String capabilityHash, String configurationHash) {}
}
