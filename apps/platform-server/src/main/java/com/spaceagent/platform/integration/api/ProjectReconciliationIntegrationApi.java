package com.spaceagent.platform.integration.api;

import com.spaceagent.platform.project.api.ProjectReconciliationApplicationApi;

public interface ProjectReconciliationIntegrationApi {
    ProjectReconciliationApplicationApi.ReconciliationStepView onBaseDrift(BaseDriftCommand command);
    ProjectReconciliationApplicationApi.ReconciliationStepView resolveAndResume(ResolveCommand command);

    record BaseDriftCommand(String tenantId, String userId, String ownerUserId, String projectId,
            String projectDirectoryId, String taskId, String taskPlanId, String planStepId,
            String executionId, String barrierId, String sourceMergeId, String sourceRepositoryId,
            String expectedBaseCommit, String actualBaseCommit, String originalPatchArtifactId,
            String originalCommitProposalArtifactId, String originalTestReportArtifactId, String originalReviewId) {}
    record ResolveCommand(String tenantId, String userId, String projectId, String reconciliationStepId,
            long expectedRevision, String patchArtifactId, String commitProposalArtifactId,
            String testReportArtifactId, String reviewId, String sourceMergeId,
            String expectedBaseCommit, String actualBaseCommit) {}
}
