package com.spaceagent.platform.runtime.domain;

import java.util.Optional;

/** Append-only Runtime persistence port for immutable per-step assignment evidence. */
public interface ProjectPlanStepAssignmentRepository {
    void insert(ProjectPlanStepAssignment assignment);

    Optional<ProjectPlanStepAssignment> findById(String id);

    Optional<ProjectPlanStepAssignment> findLatest(
            String tenantId, String ownerId, String projectId, String taskPlanId, String planStepId);

    Optional<ProjectPlanStepAssignment> findLatestForUpdate(
            String tenantId, String ownerId, String projectId, String taskPlanId, String planStepId);
}
