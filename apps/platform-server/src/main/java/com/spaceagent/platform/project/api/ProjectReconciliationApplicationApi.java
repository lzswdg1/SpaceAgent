package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.ProjectReconciliationStepState;

import java.time.Instant;

/**
 * Public Project boundary for the reconciliation lifecycle of a SourceMerge base drift.
 *
 * <p>Integration may call this API, but Runtime is only a reader/projection consumer and never creates
 * or resolves a Project reconciliation Step directly.
 */
public interface ProjectReconciliationApplicationApi {

    ReconciliationStepView createForBaseDrift(CreateForBaseDriftCommand command);

    ReconciliationStepView get(GetQuery query);

    ReconciliationStepView recordProposal(RecordProposalCommand command);

    /**
     * Resolves only after the implementation has verified Patch, Commit, Test, Review and SourceMerge
     * CAS evidence through their authoritative public owner APIs.
     */
    ReconciliationStepView resolve(ResolveCommand command);

    /** UNKNOWN remains fail-closed; this command never retries a Git effect. */
    ReconciliationStepView blockUnknown(BlockUnknownCommand command);

    record CreateForBaseDriftCommand(
            String tenantId,
            String userId,
            String ownerUserId,
            String projectId,
            String projectDirectoryId,
            String taskPlanId,
            String planStepId,
            String executionId,
            String barrierId,
            String sourceMergeId,
            String expectedBaseCommit,
            String actualBaseCommit,
            String originalPatchArtifactId,
            String originalCommitProposalArtifactId,
            String originalTestReportArtifactId,
            String originalReviewId) {
    }

    record GetQuery(String tenantId, String userId, String projectId, String reconciliationStepId) {
    }

    record RecordProposalCommand(
            String tenantId,
            String userId,
            String projectId,
            String reconciliationStepId,
            long expectedRevision,
            String workspaceId,
            String proposalId,
            String baseCommit) {
    }

    record ResolveCommand(
            String tenantId,
            String userId,
            String projectId,
            String reconciliationStepId,
            long expectedRevision,
            ResolutionEvidence evidence) {
    }

    record BlockUnknownCommand(
            String tenantId,
            String userId,
            String projectId,
            String reconciliationStepId,
            long expectedRevision) {
    }

    record ResolutionEvidence(
            String patchArtifactId,
            String commitProposalArtifactId,
            String testReportArtifactId,
            String reviewId,
            String sourceMergeId,
            String expectedBaseCommit,
            String actualBaseCommit) {
    }

    record ReconciliationStepView(
            String id,
            String tenantId,
            String ownerUserId,
            String projectId,
            String projectDirectoryId,
            String taskPlanId,
            String planStepId,
            String executionId,
            String barrierId,
            String sourceMergeId,
            String expectedBaseCommit,
            String actualBaseCommit,
            String originalPatchArtifactId,
            String originalCommitProposalArtifactId,
            String originalTestReportArtifactId,
            String originalReviewId,
            ProjectReconciliationStepState state,
            String workspaceId,
            String proposalId,
            String proposalBaseCommit,
            ResolutionEvidence resolution,
            String blockedCode,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt) {
    }
}
