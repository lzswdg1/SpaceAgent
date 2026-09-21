package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.ProjectRunHandoffState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectRunHandoffApplicationApi {
    HandoffView create(CreateCommand command);
    HandoffView get(Query query);
    HandoffPage list(ListQuery query);
    Optional<HandoffView> findBySourceCodingJobId(String codingJobId);
    HandoffView replayBySource(ReplayCommand command);
    Optional<HandoffView> findByTargetCodingJobId(String codingJobId);
    HandoffView attachTargetRun(String targetCodingJobId, String targetAgentRunId);
    HandoffView readyForFinalization(String targetCodingJobId);
    Optional<FinalizationClaim> claimFinalization(
            String workerId, int leaseSeconds, int maximumAttempts);
    HandoffView heartbeat(FinalizationCommand command, int leaseSeconds);
    HandoffView complete(FinalizationCommand command, String memoryKey);
    HandoffView fail(FinalizationCommand command, String safeErrorCode, boolean blocked);

    record CreateCommand(
            String id, String tenantId, String ownerId, String projectId,
            String projectDirectoryId, String sourceRepositoryId, String rootTaskId,
            String taskId, String taskPlanId, String planStepId, String baseRef,
            String sourceCodingJobId, String sourceAgentRunId,
            String workspaceId, String recoverySnapshotId, String recoverySnapshotHash,
            String targetConversationId, String targetAgentId, String reviewerAgentId,
            String targetCodingJobId, String idempotencyKey) {}
    record Query(String tenantId, String ownerId, String projectId, String handoffId) {}
    record ReplayCommand(
            String sourceCodingJobId, String idempotencyKey, String targetConversationId,
            String targetAgentId, String reviewerAgentId) {}
    record ListQuery(String tenantId, String ownerId, String projectId, int page, int pageSize) {}
    record FinalizationCommand(
            String handoffId, String workerId, String claimToken, long fencingToken) {}
    record FinalizationClaim(
            HandoffView handoff, String claimToken, long fencingToken, Instant leaseUntil) {}
    record HandoffPage(List<HandoffView> items, int page, int pageSize, long total) {}
    record HandoffView(
            String id, String tenantId, String ownerId, String projectId,
            String projectDirectoryId, String sourceRepositoryId, String rootTaskId,
            String taskId, String taskPlanId, String planStepId, String baseRef,
            String sourceCodingJobId, String sourceAgentRunId,
            String workspaceId, String recoverySnapshotId, String recoverySnapshotHash,
            String targetConversationId, String targetAgentId, String reviewerAgentId,
            String targetCodingJobId, String targetAgentRunId,
            ProjectRunHandoffState state, String memoryKey, String safeErrorCode, int attempt,
            long revision, Instant createdAt, Instant updatedAt, Instant completedAt) {}
}
