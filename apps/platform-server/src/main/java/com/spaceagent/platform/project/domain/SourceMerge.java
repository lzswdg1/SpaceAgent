package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.Objects;

/** Durable Project-owned source integration state; cross-module evidence IDs are opaque. */
public record SourceMerge(
        String id,
        String tenantId,
        String projectId,
        String taskId,
        String sourceRepositoryId,
        String workspaceId,
        String agentRunId,
        String reviewId,
        String commitProposalArtifactId,
        String targetRef,
        String expectedBaseCommit,
        String patchHash,
        String commitMessage,
        String idempotencyHash,
        String inputHash,
        SourceMergeState state,
        String preparedCommit,
        String actualTargetCommit,
        String governanceApprovalId,
        String failureCode,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant appliedAt,
        Instant rolledBackAt) {

    public SourceMerge {
        require(id, "id");
        require(tenantId, "tenantId");
        require(projectId, "projectId");
        require(taskId, "taskId");
        require(sourceRepositoryId, "sourceRepositoryId");
        require(workspaceId, "workspaceId");
        require(agentRunId, "agentRunId");
        require(reviewId, "reviewId");
        require(commitProposalArtifactId, "commitProposalArtifactId");
        require(targetRef, "targetRef");
        requireCommit(expectedBaseCommit, "expectedBaseCommit");
        requireHash(patchHash, "patchHash");
        require(commitMessage, "commitMessage");
        requireHash(idempotencyHash, "idempotencyHash");
        requireHash(inputHash, "inputHash");
        Objects.requireNonNull(state, "state");
        require(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        if (preparedCommit != null) requireCommit(preparedCommit, "preparedCommit");
        if (actualTargetCommit != null) requireCommit(actualTargetCommit, "actualTargetCommit");
        if (state == SourceMergeState.APPLIED_LOCAL && appliedAt == null) {
            throw new IllegalArgumentException("APPLIED_LOCAL requires appliedAt");
        }
        if (state == SourceMergeState.ROLLED_BACK && rolledBackAt == null) {
            throw new IllegalArgumentException("ROLLED_BACK requires rolledBackAt");
        }
    }

    public SourceMerge transition(
            SourceMergeState next,
            String nextPreparedCommit,
            String nextActualTargetCommit,
            String nextApprovalId,
            String nextFailureCode,
            Instant now) {
        return new SourceMerge(
                id, tenantId, projectId, taskId, sourceRepositoryId, workspaceId,
                agentRunId, reviewId, commitProposalArtifactId, targetRef,
                expectedBaseCommit, patchHash, commitMessage, idempotencyHash, inputHash,
                next, nextPreparedCommit, nextActualTargetCommit, nextApprovalId,
                nextFailureCode, revision + 1, createdBy, createdAt, now,
                next == SourceMergeState.APPLIED_LOCAL ? now : appliedAt,
                next == SourceMergeState.ROLLED_BACK ? now : rolledBackAt);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireCommit(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{40,64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase Git object id");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a SHA-256 value");
        }
    }
}
