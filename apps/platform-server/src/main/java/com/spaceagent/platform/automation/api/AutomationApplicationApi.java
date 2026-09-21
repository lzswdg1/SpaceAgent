package com.spaceagent.platform.automation.api;

import java.time.Instant;
import java.util.List;

public interface AutomationApplicationApi {

    List<ScheduledTaskView> list(String tenantId, String userId, String agentId);

    ScheduledTaskView get(String tenantId, String userId, String agentId, String scheduleId);

    ScheduledTaskView create(CreateScheduleCommand command);

    ScheduledTaskView update(UpdateScheduleCommand command);

    void archive(ActorScheduleCommand command);

    ScheduledTaskView pause(ActorScheduleCommand command);

    ScheduledTaskView resume(ActorScheduleCommand command);

    TaskExecutionView trigger(TriggerCommand command);

    List<TaskExecutionView> executions(
            String tenantId, String userId, String agentId, String scheduleId, int limit);

    boolean materializeNextDue();

    boolean dispatchNextApproved();

    void executeDispatched(ExecuteDispatchedCommand command);

    int reconcileUnknownExecutions(int staleAfterSeconds, int limit);

    record CreateScheduleCommand(
            String tenantId,
            String userId,
            String agentId,
            String description,
            String prompt,
            String type,
            String cronExpr,
            Instant scheduledAt,
            String timezone,
            int maxRetries) {}

    record UpdateScheduleCommand(
            String tenantId,
            String userId,
            String agentId,
            String scheduleId,
            String description,
            String prompt,
            String cronExpr,
            Instant scheduledAt,
            String timezone,
            Integer maxRetries,
            Long expectedRevision) {}

    record ActorScheduleCommand(
            String tenantId, String userId, String agentId, String scheduleId) {}

    record TriggerCommand(
            String tenantId,
            String userId,
            String agentId,
            String scheduleId,
            String idempotencyKey,
            String approvalId) {}

    record ExecuteDispatchedCommand(
            String executionId,
            String dispatchRunId,
            String leaseToken,
            long fencingToken) {}

    record ScheduledTaskView(
            String id,
            String agentId,
            String description,
            String prompt,
            String type,
            String cronExpr,
            Instant scheduledAt,
            String timezone,
            String status,
            Instant nextRunAt,
            Instant lastRunAt,
            String lastRunStatus,
            String lastError,
            long runCount,
            int maxRetries,
            String bullJobId,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    record TokenUsage(int inputTokens, int outputTokens) {}

    record TaskExecutionView(
            String id,
            String taskId,
            String sessionId,
            String agentRunId,
            String status,
            String approvalId,
            Instant scheduledFor,
            Instant startedAt,
            Instant completedAt,
            String error,
            TokenUsage tokenUsage,
            Instant createdAt) {}
}
