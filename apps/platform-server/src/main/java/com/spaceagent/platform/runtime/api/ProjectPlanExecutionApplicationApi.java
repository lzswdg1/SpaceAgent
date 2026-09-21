package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionDesiredState;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectPlanExecutionApplicationApi {
    ExecutionView start(StartCommand command);

    ExecutionView get(Query query);

    ExecutionPage list(ListQuery query);

    Optional<ExecutionView> getActiveByTaskPlan(QueryByTaskPlan query);

    Optional<ExecutionView> getByTaskPlan(QueryByTaskPlan query);

    ControlView getControl(Query query);

    ExecutionView begin(TransitionCommand command);

    ExecutionView requestPause(PauseCommand command);

    ExecutionView acknowledgePause(TransitionCommand command);

    ExecutionView resume(ResumeCommand command);

    ExecutionView requestCancel(CancelCommand command);

    ExecutionView acknowledgeCancel(TransitionCommand command);

    List<ExecutionView> controlTransitions(int limit);

    ExecutionView block(TransitionCommand command, String safeErrorCode);

    /** Runtime-only read projection of a Project-owned reconciliation wait. */
    ExecutionView waitForReconciliation(ReconciliationWaitCommand command);

    /** Explicit recovery path; Integration invokes it only after Project reports RESOLVED evidence. */
    ExecutionView resumeAfterReconciliation(ReconciliationResumeCommand command);

    ExecutionView fail(TransitionCommand command, String safeErrorCode);

    ExecutionView complete(TransitionCommand command);

    ExecutionView reset(TransitionCommand command);

    record StartCommand(
            String id,
            String tenantId,
            String ownerId,
            String projectId,
            String projectDirectoryId,
            String conversationId,
            String sourceRepositoryId,
            String rootTaskId,
            String taskPlanId,
            String agentId,
            String primaryConfigurationHash,
            String reviewerAgentId,
            String baseRef,
            String idempotencyKey) {}

    record Query(
            String tenantId,
            String ownerId,
            String projectId,
            String taskPlanId,
            String executionId) {
        public Query(String tenantId, String ownerId, String projectId, String executionId) {
            this(tenantId, ownerId, projectId, null, executionId);
        }
    }

    record QueryByTaskPlan(
            String tenantId,
            String ownerId,
            String taskPlanId) {}

    record ListQuery(
            String tenantId,
            String ownerId,
            String projectId,
            String taskPlanId,
            int page,
            int pageSize) {
        public ListQuery(
                String tenantId, String ownerId, String projectId, int page, int pageSize) {
            this(tenantId, ownerId, projectId, null, page, pageSize);
        }
    }

    record TransitionCommand(
            String tenantId,
            String ownerId,
            String projectId,
            String taskPlanId,
            String executionId) {}

    record ReconciliationWaitCommand(
            String tenantId, String ownerId, String projectId, String taskPlanId,
            String executionId, long expectedRevision, String reconciliationStepId) {}

    record ReconciliationResumeCommand(
            String tenantId, String ownerId, String projectId, String taskPlanId,
            String executionId, long expectedRevision, String reconciliationStepId) {}

    record PauseCommand(
            String tenantId,
            String ownerId,
            String projectId,
            String taskPlanId,
            String executionId,
            long expectedRevision,
            String reason) {}

    record ResumeCommand(
            String tenantId,
            String ownerId,
            String projectId,
            String taskPlanId,
            String executionId,
            long expectedRevision) {}

    record CancelCommand(
            String tenantId,
            String ownerId,
            String projectId,
            String taskPlanId,
            String executionId,
            long expectedRevision,
            String reason) {}

    record ExecutionPage(List<ExecutionView> items, int page, int pageSize, long total) {
        public ExecutionPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** Redacted control-plane projection; it contains no prompt or workspace content. */
    record ControlView(
            String executionId,
            ProjectPlanExecutionState state,
            ProjectPlanExecutionDesiredState desiredState,
            String safeErrorCode,
            boolean controlReasonPresent,
            long revision,
            int activeJobCount,
            Instant updatedAt,
            Instant completedAt) {}

    record ExecutionView(
            String id,
            String tenantId,
            String ownerId,
            String projectId,
            String projectDirectoryId,
            String conversationId,
            String sourceRepositoryId,
            String rootTaskId,
            String taskPlanId,
            String agentId,
            String primaryConfigurationHash,
            String reviewerAgentId,
            String baseRef,
            ProjectPlanExecutionState state,
            String safeErrorCode,
            int attempt,
            long revision,
            Instant createdAt,
            Instant startedAt,
            Instant updatedAt,
            Instant completedAt,
            List<ActiveJobView> activeJobs) {
        public ExecutionView {
            activeJobs = activeJobs == null ? List.of() : List.copyOf(activeJobs);
        }
    }

    record ActiveJobView(
            String id,
            String planStepId,
            ProjectCodingJobState state,
            String workspaceId,
            String codingRunId,
            String reviewerRunId,
            long revision,
            Instant updatedAt) {}
}
