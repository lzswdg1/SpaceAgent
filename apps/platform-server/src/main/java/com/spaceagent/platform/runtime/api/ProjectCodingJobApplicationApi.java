package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectCodingJobApplicationApi {
    JobView enqueue(EnqueueCommand command);
    JobView get(Query query);
    JobPage list(ListQuery query);
    Optional<ClaimView> claim(String workerId, int leaseSeconds, int maximumAttempts);
    JobView heartbeat(ClaimCommand command, int leaseSeconds);
    JobView releaseForPause(ClaimCommand command);
    JobView attachWorkspace(ClaimCommand command, String workspaceId);
    JobView attachCodingRun(ClaimCommand command, String runId);
    JobView initializeContext(ClaimCommand command, String contextJson);
    JobView savePendingTool(ClaimCommand command, String pendingToolJson);
    JobView recordToolResult(ClaimCommand command, String contextJson, int nextIteration);
    JobView waitForApproval(ClaimCommand command, String approvalId);
    JobView resume(ResumeCommand command);
    JobView handoff(HandoffCommand command);
    JobView cancel(CancelCommand command);
    JobView recordPrepared(ClaimCommand command, String patchArtifactId, String commitArtifactId);
    JobView attachReviewerRun(ClaimCommand command, String reviewerRunId);
    JobView recordReview(ClaimCommand command, String reviewId, boolean approved,
                         String nextContextJson, int nextReviewRound);
    JobView complete(ClaimCommand command, String sourceMergeId);
    JobView completeAndEnqueueBarrier(BarrierCompletionCommand command);
    JobView fail(FailCommand command);

    record EnqueueCommand(
            String tenantId, String ownerId, String projectId, String projectDirectoryId,
            String conversationId, String sourceRepositoryId, String rootTaskId, String taskId,
            String taskPlanId, String executionId, String planStepId, String agentId, String primaryConfigurationHash,
            String reviewerConfigurationHash, String baseRef, String idempotencyKey,
            String reviewerAgentId) {
        public EnqueueCommand(String tenantId,String ownerId,String projectId,String projectDirectoryId,
                String conversationId,String sourceRepositoryId,String rootTaskId,String taskId,
                String taskPlanId,String executionId,String planStepId,String agentId,String primaryConfigurationHash,
                String reviewerConfigurationHash,String baseRef,String idempotencyKey){
            this(tenantId,ownerId,projectId,projectDirectoryId,conversationId,sourceRepositoryId,
                    rootTaskId,taskId,taskPlanId,executionId,planStepId,agentId,primaryConfigurationHash,
                    reviewerConfigurationHash,baseRef,idempotencyKey,null);
        }
    }
    record Query(String tenantId, String ownerId, String projectId, String taskPlanId,
                 String planStepId, String jobId) {}
    record ListQuery(String tenantId, String ownerId, String projectId, String taskPlanId,
                     String planStepId, int page, int pageSize) {}
    record ClaimCommand(String jobId, String workerId, String claimToken, long fencingToken) {}
    record ResumeCommand(String tenantId, String ownerId, String projectId, String taskPlanId,
                         String planStepId, String jobId, String approvalId) {}
    record HandoffCommand(String tenantId, String ownerId, String projectId, String taskPlanId,
                          String planStepId, String jobId) {}
    record CancelCommand(String tenantId, String ownerId, String projectId, String taskPlanId,
                         String planStepId, String jobId, long expectedRevision, String reason) {}
    record FailCommand(String jobId, String workerId, String claimToken, long fencingToken,
                       String safeErrorCode, boolean blocked) {}
    record BarrierCompletionCommand(
            ClaimCommand claim, String sourceMergeId, int applyIndex) {}
    record ClaimView(JobView job, String claimToken, long fencingToken, Instant leaseUntil,
                     String contextJson, String pendingToolJson) {}
    record JobPage(List<JobView> items, int page, int pageSize, long total) {
        public JobPage { items = items == null ? List.of() : List.copyOf(items); }
    }
    record JobView(
            String id, String tenantId, String ownerId, String projectId,
            String projectDirectoryId, String conversationId, String sourceRepositoryId,
            String rootTaskId, String taskId, String taskPlanId, String executionId, String planStepId,
            String agentId, String primaryConfigurationHash, String reviewerConfigurationHash, String baseRef,
            ProjectCodingJobState state, String workspaceId, String codingRunId,
            String reviewerRunId, int iteration, int reviewRound, String pendingToolName,
            String pendingApprovalId, String patchArtifactId, String commitArtifactId,
            String reviewId, String sourceMergeId, String safeErrorCode, int attempt,
            long revision, Instant createdAt, Instant startedAt, Instant updatedAt,
            Instant completedAt, String reviewerAgentId) {
        public JobView(String id,String tenantId,String ownerId,String projectId,
                String projectDirectoryId,String conversationId,String sourceRepositoryId,
                String rootTaskId,String taskId,String taskPlanId,String executionId,String planStepId,
                String agentId,String primaryConfigurationHash,String reviewerConfigurationHash,String baseRef,
                ProjectCodingJobState state,String workspaceId,String codingRunId,String reviewerRunId,
                int iteration,int reviewRound,String pendingToolName,String pendingApprovalId,
                String patchArtifactId,String commitArtifactId,String reviewId,String sourceMergeId,
                String safeErrorCode,int attempt,long revision,Instant createdAt,Instant startedAt,
                Instant updatedAt,Instant completedAt){
            this(id,tenantId,ownerId,projectId,projectDirectoryId,conversationId,sourceRepositoryId,
                    rootTaskId,taskId,taskPlanId,executionId,planStepId,agentId,primaryConfigurationHash,
                    reviewerConfigurationHash,baseRef,state,workspaceId,codingRunId,reviewerRunId,
                    iteration,reviewRound,pendingToolName,pendingApprovalId,patchArtifactId,
                    commitArtifactId,reviewId,sourceMergeId,safeErrorCode,attempt,revision,createdAt,
                    startedAt,updatedAt,completedAt,null);
        }
        public JobView(
                String id, String tenantId, String ownerId, String projectId,
                String projectDirectoryId, String conversationId, String sourceRepositoryId,
                String rootTaskId, String taskId, String taskPlanId, String planStepId,
                String agentId, String primaryConfigurationHash, String reviewerConfigurationHash,
                String baseRef, ProjectCodingJobState state, String workspaceId,
                String codingRunId, String reviewerRunId, int iteration, int reviewRound,
                String pendingToolName, String pendingApprovalId, String patchArtifactId,
                String commitArtifactId, String reviewId, String sourceMergeId,
                String safeErrorCode, int attempt, long revision, Instant createdAt,
                Instant startedAt, Instant updatedAt, Instant completedAt) {
            this(id, tenantId, ownerId, projectId, projectDirectoryId, conversationId,
                    sourceRepositoryId, rootTaskId, taskId, taskPlanId, null, planStepId,
                    agentId, primaryConfigurationHash, reviewerConfigurationHash, baseRef, state,
                    workspaceId, codingRunId, reviewerRunId, iteration, reviewRound,
                    pendingToolName, pendingApprovalId, patchArtifactId, commitArtifactId,
                    reviewId, sourceMergeId, safeErrorCode, attempt, revision, createdAt,
                    startedAt, updatedAt, completedAt, null);
        }
    }
}
