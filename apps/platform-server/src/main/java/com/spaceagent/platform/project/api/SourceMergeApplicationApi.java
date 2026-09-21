package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.SourceMergeState;

import java.time.Instant;

public interface SourceMergeApplicationApi {

    SourceMergeView prepare(PrepareCommand command);
    SourceMergeView get(Query query);
    SourceMergeView apply(ApplyCommand command);
    SourceMergeView rollback(RollbackCommand command);
    SourceMergeView reconcile(ReconcileCommand command);

    record PrepareCommand(
            String tenantId,
            String userId,
            String projectId,
            String taskId,
            String sourceRepositoryId,
            String workspaceId,
            String agentRunId,
            String reviewId,
            String commitProposalArtifactId,
            String expectedBaseCommit,
            String patchHash,
            String commitMessage,
            String idempotencyHash,
            String inputHash) {}

    record Query(String tenantId, String userId, String projectId, String mergeId) {}

    record ApplyCommand(
            String tenantId,
            String userId,
            String projectId,
            String mergeId,
            String governanceApprovalId) {}

    record RollbackCommand(
            String tenantId,
            String userId,
            String projectId,
            String mergeId,
            String governanceApprovalId) {}

    record ReconcileCommand(
            String tenantId,
            String userId,
            String projectId,
            String mergeId) {}

    record SourceMergeView(
            String id,
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
            SourceMergeState state,
            String preparedCommit,
            String actualTargetCommit,
            String governanceApprovalId,
            String failureCode,
            long revision,
            boolean remoteUpdated,
            String manualDeliveryInstruction,
            Instant createdAt,
            Instant updatedAt,
            Instant appliedAt,
            Instant rolledBackAt) {}
}
