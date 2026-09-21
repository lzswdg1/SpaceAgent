package com.spaceagent.platform.project.domain;

import java.util.Optional;

/** Project-owned durable store for independent SourceMerge reconciliation Steps. */
public interface ProjectReconciliationStepRepository {
    void insert(ProjectReconciliationStep step);

    Optional<ProjectReconciliationStep> findById(
            String tenantId, String ownerUserId, String projectId, String reconciliationStepId);

    Optional<ProjectReconciliationStep> findBySourceMergeId(
            String tenantId, String ownerUserId, String projectId, String sourceMergeId);

    boolean update(
            ProjectReconciliationStep step,
            long expectedRevision,
            ProjectReconciliationStepState expectedState);
}
