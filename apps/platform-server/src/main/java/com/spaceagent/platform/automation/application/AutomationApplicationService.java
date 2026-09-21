package com.spaceagent.platform.automation.application;

import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.automation.api.AutomationApplicationApi;
import com.spaceagent.platform.automation.domain.AutomationExecution;
import com.spaceagent.platform.automation.domain.AutomationExecutionState;
import com.spaceagent.platform.automation.domain.AutomationRepository;
import com.spaceagent.platform.automation.domain.AutomationSchedule;
import com.spaceagent.platform.automation.domain.AutomationScheduleState;
import com.spaceagent.platform.automation.domain.AutomationScheduleType;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class AutomationApplicationService implements AutomationApplicationApi {

    private final AutomationRepository repository;
    private final AutomationScheduleCalculator calculator;
    private final AutomationExecutionCoordinator executions;
    private final IdentityApplicationApi identity;
    private final AgentApplicationApi agents;
    private final IdGenerator ids;

    public AutomationApplicationService(
            AutomationRepository repository,
            AutomationScheduleCalculator calculator,
            AutomationExecutionCoordinator executions,
            IdentityApplicationApi identity,
            AgentApplicationApi agents,
            IdGenerator ids) {
        this.repository = repository;
        this.calculator = calculator;
        this.executions = executions;
        this.identity = identity;
        this.agents = agents;
        this.ids = ids;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduledTaskView> list(String tenantId, String userId, String agentId) {
        requireAgent(tenantId, userId, agentId);
        return repository.findSchedules(tenantId, userId, agentId).stream()
                .map(AutomationApplicationService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ScheduledTaskView get(
            String tenantId, String userId, String agentId, String scheduleId) {
        return view(requireSchedule(tenantId, userId, agentId, scheduleId));
    }

    @Override
    @Transactional
    public ScheduledTaskView create(CreateScheduleCommand command) {
        requireAgent(command.tenantId(), command.userId(), command.agentId());
        Instant now = repository.currentTime();
        AutomationScheduleType type = type(command.type());
        String timezone = calculator.validateTimezone(command.timezone());
        String cron = type == AutomationScheduleType.PERIODIC
                ? calculator.normalizeCron(command.cronExpr()) : null;
        Instant scheduledAt = type == AutomationScheduleType.ONE_TIME
                ? command.scheduledAt() : null;
        Instant next = calculator.initialFireAt(
                type, cron, scheduledAt, timezone, now);
        AutomationSchedule schedule = new AutomationSchedule(
                ids.nextId(), command.tenantId(), command.userId(), command.agentId(),
                requireText(command.description(), "description", 200),
                requireText(command.prompt(), "prompt", 32_000), type, cron, scheduledAt,
                timezone, AutomationScheduleState.ACTIVE, next, null, null, null,
                0, retries(command.maxRetries()), 1, now, now, null);
        repository.createSchedule(schedule);
        return view(schedule);
    }

    @Override
    @Transactional
    public ScheduledTaskView update(UpdateScheduleCommand command) {
        AutomationSchedule current = requireSchedule(
                command.tenantId(), command.userId(), command.agentId(), command.scheduleId());
        long expected = command.expectedRevision() == null
                ? current.revision() : command.expectedRevision();
        if (expected != current.revision()) {
            throw revisionConflict();
        }
        Instant now = repository.currentTime();
        String timezone = command.timezone() == null
                ? current.timezone() : calculator.validateTimezone(command.timezone());
        String cron = current.cronExpression();
        Instant scheduledAt = current.scheduledAt();
        if (current.type() == AutomationScheduleType.PERIODIC && command.cronExpr() != null) {
            cron = calculator.normalizeCron(command.cronExpr());
        }
        if (current.type() == AutomationScheduleType.ONE_TIME && command.scheduledAt() != null) {
            scheduledAt = command.scheduledAt();
        }
        Instant next = current.state() == AutomationScheduleState.ACTIVE
                ? calculator.initialFireAt(
                        current.type(), cron, scheduledAt, timezone, now)
                : current.nextFireAt();
        AutomationSchedule updated = copy(
                current,
                command.description() == null ? current.description()
                        : requireText(command.description(), "description", 200),
                command.prompt() == null ? current.prompt()
                        : requireText(command.prompt(), "prompt", 32_000),
                cron, scheduledAt, timezone, current.state(), next,
                command.maxRetries() == null ? current.maxRetries()
                        : retries(command.maxRetries()),
                current.archivedAt(), now);
        return repository.updateSchedule(updated, expected)
                .map(AutomationApplicationService::view)
                .orElseThrow(AutomationApplicationService::revisionConflict);
    }

    @Override
    @Transactional
    public void archive(ActorScheduleCommand command) {
        AutomationSchedule current = requireSchedule(command);
        Instant now = repository.currentTime();
        updateState(current, AutomationScheduleState.ARCHIVED, null, now, now);
    }

    @Override
    @Transactional
    public ScheduledTaskView pause(ActorScheduleCommand command) {
        AutomationSchedule current = requireSchedule(command);
        if (current.state() == AutomationScheduleState.PAUSED) {
            return view(current);
        }
        if (current.state() != AutomationScheduleState.ACTIVE) {
            throw stateConflict(current);
        }
        return view(updateState(
                current, AutomationScheduleState.PAUSED, current.nextFireAt(),
                repository.currentTime(), null));
    }

    @Override
    @Transactional
    public ScheduledTaskView resume(ActorScheduleCommand command) {
        AutomationSchedule current = requireSchedule(command);
        if (current.state() == AutomationScheduleState.ACTIVE) {
            return view(current);
        }
        if (current.state() != AutomationScheduleState.PAUSED) {
            throw stateConflict(current);
        }
        Instant now = repository.currentTime();
        Instant next = current.type() == AutomationScheduleType.PERIODIC
                ? calculator.nextCron(current.cronExpression(), current.timezone(), now)
                : current.scheduledAt().isAfter(now) ? current.scheduledAt() : now.plusSeconds(1);
        return view(updateState(current, AutomationScheduleState.ACTIVE, next, now, null));
    }

    @Override
    @Transactional
    public TaskExecutionView trigger(TriggerCommand command) {
        AutomationSchedule schedule = requireSchedule(
                command.tenantId(), command.userId(), command.agentId(), command.scheduleId());
        if (schedule.state() == AutomationScheduleState.ARCHIVED
                || schedule.state() == AutomationScheduleState.FAILED) {
            throw stateConflict(schedule);
        }
        String key = command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                ? ids.nextId() : command.idempotencyKey().trim();
        if (key.length() > 190) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 190 characters");
        }
        AutomationExecution execution = executions.createOccurrence(
                schedule, "manual:" + key, repository.currentTime(),
                AutomationTriggerType.MANUAL, command.approvalId());
        return view(execution);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskExecutionView> executions(
            String tenantId, String userId, String agentId, String scheduleId, int limit) {
        requireSchedule(tenantId, userId, agentId, scheduleId);
        int bounded = limit <= 0 ? 20 : Math.min(limit, 200);
        return repository.findExecutions(scheduleId, bounded).stream()
                .map(AutomationApplicationService::view).toList();
    }

    @Override
    @Transactional
    public boolean materializeNextDue() {
        var due = repository.lockNextDueSchedule().orElse(null);
        if (due == null) {
            return false;
        }
        AutomationSchedule schedule = due.schedule();
        Instant scheduledFor = schedule.nextFireAt();
        executions.createOccurrence(
                schedule, "scheduled:" + scheduledFor, scheduledFor,
                AutomationTriggerType.SCHEDULED, null);
        AutomationScheduleState state = schedule.type() == AutomationScheduleType.ONE_TIME
                ? AutomationScheduleState.COMPLETED : AutomationScheduleState.ACTIVE;
        Instant next = schedule.type() == AutomationScheduleType.ONE_TIME
                ? null : calculator.nextCron(
                        schedule.cronExpression(), schedule.timezone(),
                        scheduledFor.isAfter(due.databaseNow())
                                ? scheduledFor : due.databaseNow());
        updateState(schedule, state, next, due.databaseNow(), null);
        return true;
    }

    @Override
    @Transactional
    public boolean dispatchNextApproved() {
        return executions.dispatchNextApproved();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void executeDispatched(ExecuteDispatchedCommand command) {
        executions.executeDispatched(command);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int reconcileUnknownExecutions(int staleAfterSeconds, int limit) {
        int stale = Math.max(60, Math.min(staleAfterSeconds, 86_400));
        int bounded = Math.max(1, Math.min(limit, 100));
        return executions.reconcileUnknown(stale, bounded);
    }

    private AutomationSchedule requireSchedule(ActorScheduleCommand command) {
        return requireSchedule(
                command.tenantId(), command.userId(), command.agentId(), command.scheduleId());
    }

    private AutomationSchedule requireSchedule(
            String tenantId, String userId, String agentId, String scheduleId) {
        requireAgent(tenantId, userId, agentId);
        return repository.findSchedule(scheduleId)
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.ownerId().equals(userId))
                .filter(value -> value.agentId().equals(agentId))
                .filter(value -> value.state() != AutomationScheduleState.ARCHIVED)
                .orElseThrow(() -> new BusinessException(
                        "Automation schedule not found", HttpStatus.NOT_FOUND,
                        "AUTOMATION_SCHEDULE_NOT_FOUND"));
    }

    private void requireAgent(String tenantId, String userId, String agentId) {
        identity.findTenantMembership(tenantId, userId)
                .filter(value -> value.status() == TenantMembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Active Organization membership is required", HttpStatus.FORBIDDEN,
                        "AUTOMATION_MEMBERSHIP_REQUIRED"));
        agents.findById(agentId)
                .filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> userId.equals(value.ownerId()))
                .filter(value -> value.status() == AgentDefinitionStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Agent not found", HttpStatus.NOT_FOUND,
                        "AUTOMATION_AGENT_NOT_FOUND"));
    }

    private AutomationSchedule updateState(
            AutomationSchedule current,
            AutomationScheduleState state,
            Instant nextFireAt,
            Instant updatedAt,
            Instant archivedAt) {
        AutomationSchedule updated = copy(
                current, current.description(), current.prompt(), current.cronExpression(),
                current.scheduledAt(), current.timezone(), state, nextFireAt,
                current.maxRetries(), archivedAt, updatedAt);
        return repository.updateSchedule(updated, current.revision())
                .orElseThrow(AutomationApplicationService::revisionConflict);
    }

    private static AutomationSchedule copy(
            AutomationSchedule value,
            String description,
            String prompt,
            String cron,
            Instant scheduledAt,
            String timezone,
            AutomationScheduleState state,
            Instant nextFireAt,
            int maxRetries,
            Instant archivedAt,
            Instant updatedAt) {
        return new AutomationSchedule(
                value.id(), value.tenantId(), value.ownerId(), value.agentId(), description,
                prompt, value.type(), cron, scheduledAt, timezone, state, nextFireAt,
                value.lastRunAt(), value.lastRunStatus(), value.lastError(), value.runCount(),
                maxRetries, value.revision() + 1, value.createdAt(), updatedAt, archivedAt);
    }

    private static ScheduledTaskView view(AutomationSchedule value) {
        return new ScheduledTaskView(
                value.id(), value.agentId(), value.description(), value.prompt(),
                value.type().name().toLowerCase(Locale.ROOT), value.cronExpression(),
                value.scheduledAt(), value.timezone(),
                value.state().name().toLowerCase(Locale.ROOT), value.nextFireAt(),
                value.lastRunAt(), lower(value.lastRunStatus()), value.lastError(),
                value.runCount(), value.maxRetries(), null, value.revision(),
                value.createdAt(), value.updatedAt());
    }

    private static TaskExecutionView view(AutomationExecution value) {
        return new TaskExecutionView(
                value.id(), value.scheduleId(), value.conversationId(),
                value.agentRunId() == null ? value.dispatchRunId() : value.agentRunId(),
                executionState(value.state()), value.approvalId(), value.scheduledFor(),
                value.startedAt(), value.completedAt(), value.error(),
                new TokenUsage(value.inputTokens(), value.outputTokens()), value.createdAt());
    }

    private static String executionState(AutomationExecutionState state) {
        return switch (state) {
            case PENDING_DISPATCH -> "queued";
            case APPROVAL_REQUIRED -> "waiting_approval";
            case QUEUED -> "queued";
            case RUNNING -> "running";
            case SUCCEEDED -> "success";
            case FAILED -> "failed";
            case UNKNOWN -> "unknown";
            case REJECTED -> "rejected";
            case EXPIRED -> "expired";
            case CANCELLED -> "cancelled";
        };
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static AutomationScheduleType type(String value) {
        if (value == null) {
            throw new IllegalArgumentException("type is required");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "periodic" -> AutomationScheduleType.PERIODIC;
            case "one_time" -> AutomationScheduleType.ONE_TIME;
            default -> throw new IllegalArgumentException("Unsupported schedule type: " + value);
        };
    }

    private static int retries(int value) {
        if (value < 0 || value > 10) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 10");
        }
        return value;
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(field + " is required and bounded");
        }
        return value.trim();
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(
                "Automation schedule revision conflict", HttpStatus.CONFLICT,
                "AUTOMATION_SCHEDULE_REVISION_CONFLICT");
    }

    private static BusinessException stateConflict(AutomationSchedule schedule) {
        return new BusinessException(
                "Automation schedule state conflict: " + schedule.state(),
                HttpStatus.CONFLICT, "AUTOMATION_SCHEDULE_STATE_CONFLICT");
    }
}
