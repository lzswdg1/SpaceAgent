package com.spaceagent.platform.automation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AutomationRepository {

    Instant currentTime();

    void createSchedule(AutomationSchedule schedule);

    Optional<AutomationSchedule> findSchedule(String scheduleId);

    List<AutomationSchedule> findSchedules(String tenantId, String ownerId, String agentId);

    Optional<AutomationSchedule> updateSchedule(
            AutomationSchedule schedule, long expectedRevision);

    Optional<DueAutomationSchedule> lockNextDueSchedule();

    AutomationExecution createOrFindExecution(AutomationExecution execution);

    Optional<AutomationExecution> findExecution(String executionId);

    Optional<AutomationExecution> findExecutionByFireKey(String scheduleId, String fireKey);

    List<AutomationExecution> findExecutions(String scheduleId, int limit);

    Optional<AutomationExecution> lockNextApprovalRequired();

    Optional<AutomationExecution> transitionExecution(
            AutomationExecution execution, long expectedRevision);

    List<AutomationExecution> findRunningBefore(Instant cutoff, int limit);

    Optional<AutomationSchedule> recordOutcome(
            String scheduleId,
            Instant completedAt,
            AutomationExecutionState state,
            String error);
}
