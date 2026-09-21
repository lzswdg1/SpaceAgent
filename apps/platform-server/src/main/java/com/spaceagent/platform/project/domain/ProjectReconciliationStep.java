package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Project-owned reconciliation lifecycle for a SourceMerge base drift.
 *
 * <p>This aggregate deliberately remains separate from immutable TaskPlan and PlanStep structure.
 * Cross-module references are opaque IDs; callers must verify their owner evidence before transitions.
 */
public record ProjectReconciliationStep(
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
        ReconciliationProposal proposal,
        ResolutionEvidence resolution,
        String blockedCode,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt) {

    public ProjectReconciliationStep {
        requireText(id, "id");
        requireText(tenantId, "tenantId");
        requireText(ownerUserId, "ownerUserId");
        requireText(projectId, "projectId");
        requireText(projectDirectoryId, "projectDirectoryId");
        requireText(taskPlanId, "taskPlanId");
        requireText(planStepId, "planStepId");
        requireText(executionId, "executionId");
        requireText(barrierId, "barrierId");
        requireText(sourceMergeId, "sourceMergeId");
        requireCommit(expectedBaseCommit, "expectedBaseCommit");
        requireCommit(actualBaseCommit, "actualBaseCommit");
        requireText(originalPatchArtifactId, "originalPatchArtifactId");
        requireText(originalCommitProposalArtifactId, "originalCommitProposalArtifactId");
        requireText(originalTestReportArtifactId, "originalTestReportArtifactId");
        requireText(originalReviewId, "originalReviewId");
        Objects.requireNonNull(state, "state");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (state == ProjectReconciliationStepState.WAITING_RECONCILIATION
                && (proposal != null || resolution != null || blockedCode != null || resolvedAt != null)) {
            throw new IllegalArgumentException("waiting reconciliation must contain no outcome evidence");
        }
        if (state == ProjectReconciliationStepState.PROPOSAL_READY
                && (proposal == null || resolution != null || blockedCode != null || resolvedAt != null)) {
            throw new IllegalArgumentException("proposal ready requires only reconciliation proposal evidence");
        }
        if (state == ProjectReconciliationStepState.BLOCKED
                && (blockedCode == null || blockedCode.isBlank() || resolution != null || resolvedAt != null)) {
            throw new IllegalArgumentException("blocked reconciliation requires only a safe blocker code");
        }
        if (state == ProjectReconciliationStepState.RESOLVED
                && (proposal == null || resolution == null || blockedCode != null || resolvedAt == null)) {
            throw new IllegalArgumentException("resolved reconciliation requires proposal and resolution evidence");
        }
    }

    public static ProjectReconciliationStep forBaseDrift(
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
            Instant now) {
        return new ProjectReconciliationStep(
                id, tenantId, ownerUserId, projectId, projectDirectoryId, taskPlanId, planStepId,
                executionId, barrierId, sourceMergeId, expectedBaseCommit, actualBaseCommit,
                originalPatchArtifactId, originalCommitProposalArtifactId, originalTestReportArtifactId,
                originalReviewId, ProjectReconciliationStepState.WAITING_RECONCILIATION, null, null, null,
                1L, now, now, null);
    }

    public ProjectReconciliationStep recordProposal(ReconciliationProposal nextProposal, Instant now) {
        Objects.requireNonNull(nextProposal, "nextProposal");
        if (state == ProjectReconciliationStepState.PROPOSAL_READY && proposal.equals(nextProposal)) {
            return this;
        }
        requireState(ProjectReconciliationStepState.WAITING_RECONCILIATION, "record proposal");
        if (!actualBaseCommit.equals(nextProposal.baseCommit())) {
            throw new IllegalArgumentException("proposal base must match actualBaseCommit");
        }
        return copy(ProjectReconciliationStepState.PROPOSAL_READY, nextProposal, null, null, now, null);
    }

    public ProjectReconciliationStep resolve(ResolutionEvidence nextResolution, Instant now) {
        Objects.requireNonNull(nextResolution, "nextResolution");
        requireState(ProjectReconciliationStepState.PROPOSAL_READY, "resolve");
        if (!proposal.baseCommit().equals(nextResolution.expectedBaseCommit())) {
            throw new IllegalArgumentException("resolution CAS base must match proposal base");
        }
        return copy(ProjectReconciliationStepState.RESOLVED, proposal, nextResolution, null, now, now);
    }

    /** UNKNOWN Git effects are fail-closed and cannot be retried through this aggregate. */
    public ProjectReconciliationStep blockUnknown(Instant now) {
        if (state == ProjectReconciliationStepState.BLOCKED) {
            return this;
        }
        if (state == ProjectReconciliationStepState.RESOLVED) {
            throw new IllegalStateException("resolved reconciliation cannot become blocked");
        }
        return copy(ProjectReconciliationStepState.BLOCKED, proposal, null, "UNKNOWN_GIT_EFFECT", now, null);
    }

    private ProjectReconciliationStep copy(
            ProjectReconciliationStepState nextState,
            ReconciliationProposal nextProposal,
            ResolutionEvidence nextResolution,
            String nextBlockedCode,
            Instant now,
            Instant nextResolvedAt) {
        return new ProjectReconciliationStep(
                id, tenantId, ownerUserId, projectId, projectDirectoryId, taskPlanId, planStepId,
                executionId, barrierId, sourceMergeId, expectedBaseCommit, actualBaseCommit,
                originalPatchArtifactId, originalCommitProposalArtifactId, originalTestReportArtifactId,
                originalReviewId, nextState, nextProposal, nextResolution, nextBlockedCode, revision + 1,
                createdAt, Objects.requireNonNull(now, "now"), nextResolvedAt);
    }

    private void requireState(ProjectReconciliationStepState expected, String action) {
        if (state != expected) {
            throw new IllegalStateException("cannot " + action + " from " + state);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireCommit(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{40,64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase Git object id");
        }
    }

    public record ReconciliationProposal(String workspaceId, String proposalId, String baseCommit) {
        public ReconciliationProposal {
            requireText(workspaceId, "workspaceId");
            requireText(proposalId, "proposalId");
            requireCommit(baseCommit, "baseCommit");
        }
    }

    public record ResolutionEvidence(
            String patchArtifactId,
            String commitProposalArtifactId,
            String testReportArtifactId,
            String reviewId,
            String sourceMergeId,
            String expectedBaseCommit,
            String actualBaseCommit) {
        public ResolutionEvidence {
            requireText(patchArtifactId, "patchArtifactId");
            requireText(commitProposalArtifactId, "commitProposalArtifactId");
            requireText(testReportArtifactId, "testReportArtifactId");
            requireText(reviewId, "reviewId");
            requireText(sourceMergeId, "sourceMergeId");
            requireCommit(expectedBaseCommit, "expectedBaseCommit");
            requireCommit(actualBaseCommit, "actualBaseCommit");
        }
    }
}
