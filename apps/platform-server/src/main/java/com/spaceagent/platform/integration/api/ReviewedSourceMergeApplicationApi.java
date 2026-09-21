package com.spaceagent.platform.integration.api;

import com.spaceagent.platform.project.api.SourceMergeApplicationApi;

public interface ReviewedSourceMergeApplicationApi {
    SourceMergeApplicationApi.SourceMergeView prepare(PrepareCommand command);
    SourceMergeApplicationApi.SourceMergeView get(Query query);
    SourceMergeApplicationApi.SourceMergeView apply(ApplyCommand command);
    SourceMergeApplicationApi.SourceMergeView rollback(RollbackCommand command);
    SourceMergeApplicationApi.SourceMergeView reconcile(Query query);

    record PrepareCommand(
            String tenantId,
            String userId,
            String projectId,
            String reviewId,
            String commitProposalArtifactId,
            String idempotencyKey) {}

    record Query(String tenantId, String userId, String projectId, String mergeId) {}

    record ApplyCommand(
            String tenantId,
            String userId,
            String projectId,
            String mergeId,
            String approvalId) {}

    record RollbackCommand(
            String tenantId,
            String userId,
            String projectId,
            String mergeId,
            String approvalId) {}
}
