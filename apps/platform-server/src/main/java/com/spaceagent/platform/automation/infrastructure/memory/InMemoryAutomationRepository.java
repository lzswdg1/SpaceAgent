package com.spaceagent.platform.automation.infrastructure.memory;

import com.spaceagent.platform.automation.domain.AutomationExecution;
import com.spaceagent.platform.automation.domain.AutomationExecutionState;
import com.spaceagent.platform.automation.domain.AutomationRepository;
import com.spaceagent.platform.automation.domain.AutomationSchedule;
import com.spaceagent.platform.automation.domain.DueAutomationSchedule;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAutomationRepository implements AutomationRepository {

    private final Map<String, AutomationSchedule> schedules = new HashMap<>();
    private final Map<String, AutomationExecution> executions = new HashMap<>();
    private final TimeProvider time;

    public InMemoryAutomationRepository(TimeProvider time) {
        this.time = time;
    }

    @Override
    public Instant currentTime() {
        return time.now();
    }

    @Override
    public synchronized void createSchedule(AutomationSchedule schedule) {
        if (schedules.putIfAbsent(schedule.id(), schedule) != null) {
            throw new IllegalStateException("Automation schedule already exists");
        }
    }

    @Override
    public synchronized Optional<AutomationSchedule> findSchedule(String scheduleId) {
        return Optional.ofNullable(schedules.get(scheduleId));
    }

    @Override
    public synchronized List<AutomationSchedule> findSchedules(
            String tenantId, String ownerId, String agentId) {
        return schedules.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.ownerId().equals(ownerId))
                .filter(value -> value.agentId().equals(agentId))
                .filter(value -> value.state()
                        != com.spaceagent.platform.automation.domain.AutomationScheduleState.ARCHIVED)
                .sorted(Comparator.comparing(AutomationSchedule::createdAt)
                        .thenComparing(AutomationSchedule::id))
                .toList();
    }

    @Override
    public synchronized Optional<AutomationSchedule> updateSchedule(
            AutomationSchedule schedule, long expectedRevision) {
        AutomationSchedule current = schedules.get(schedule.id());
        if (current == null || current.revision() != expectedRevision
                || schedule.revision() != expectedRevision + 1) {
            return Optional.empty();
        }
        schedules.put(schedule.id(), schedule);
        return Optional.of(schedule);
    }

    @Override
    public synchronized Optional<DueAutomationSchedule> lockNextDueSchedule() {
        Instant now = currentTime();
        return schedules.values().stream()
                .filter(value -> value.state()
                        == com.spaceagent.platform.automation.domain.AutomationScheduleState.ACTIVE)
                .filter(value -> value.nextFireAt() != null && !value.nextFireAt().isAfter(now))
                .min(Comparator.comparing(AutomationSchedule::nextFireAt)
                        .thenComparing(AutomationSchedule::createdAt)
                        .thenComparing(AutomationSchedule::id))
                .map(value -> new DueAutomationSchedule(value, now));
    }

    @Override
    public synchronized AutomationExecution createOrFindExecution(
            AutomationExecution execution) {
        return executions.values().stream()
                .filter(value -> value.scheduleId().equals(execution.scheduleId()))
                .filter(value -> value.fireKey().equals(execution.fireKey()))
                .findFirst()
                .orElseGet(() -> {
                    executions.put(execution.id(), execution);
                    return execution;
                });
    }

    @Override
    public synchronized Optional<AutomationExecution> findExecution(String executionId) {
        return Optional.ofNullable(executions.get(executionId));
    }

    @Override
    public synchronized Optional<AutomationExecution> findExecutionByFireKey(
            String scheduleId, String fireKey) {
        return executions.values().stream()
                .filter(value -> value.scheduleId().equals(scheduleId))
                .filter(value -> value.fireKey().equals(fireKey))
                .findFirst();
    }

    @Override
    public synchronized List<AutomationExecution> findExecutions(String scheduleId, int limit) {
        return executions.values().stream()
                .filter(value -> value.scheduleId().equals(scheduleId))
                .sorted(Comparator.comparing(AutomationExecution::createdAt).reversed()
                        .thenComparing(AutomationExecution::id))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized Optional<AutomationExecution> lockNextApprovalRequired() {
        return executions.values().stream()
                .filter(value -> value.state() == AutomationExecutionState.APPROVAL_REQUIRED)
                .min(Comparator.comparing(AutomationExecution::updatedAt)
                        .thenComparing(AutomationExecution::id));
    }

    @Override
    public synchronized Optional<AutomationExecution> transitionExecution(
            AutomationExecution execution, long expectedRevision) {
        AutomationExecution current = executions.get(execution.id());
        if (current == null || current.revision() != expectedRevision
                || execution.revision() != expectedRevision + 1) {
            return Optional.empty();
        }
        executions.put(execution.id(), execution);
        return Optional.of(execution);
    }

    @Override
    public synchronized List<AutomationExecution> findRunningBefore(Instant cutoff, int limit) {
        return executions.values().stream()
                .filter(value -> value.state() == AutomationExecutionState.RUNNING)
                .filter(value -> value.startedAt() != null && value.startedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(AutomationExecution::startedAt))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized Optional<AutomationSchedule> recordOutcome(
            String scheduleId,
            Instant completedAt,
            AutomationExecutionState state,
            String error) {
        AutomationSchedule current = schedules.get(scheduleId);
        if (current == null) {
            return Optional.empty();
        }
        AutomationSchedule updated = new AutomationSchedule(
                current.id(), current.tenantId(), current.ownerId(), current.agentId(),
                current.description(), current.prompt(), current.type(), current.cronExpression(),
                current.scheduledAt(), current.timezone(), current.state(), current.nextFireAt(),
                completedAt, state.name(), error, current.runCount() + 1,
                current.maxRetries(), current.revision() + 1, current.createdAt(),
                completedAt, current.archivedAt());
        schedules.put(scheduleId, updated);
        return Optional.of(updated);
    }
}
