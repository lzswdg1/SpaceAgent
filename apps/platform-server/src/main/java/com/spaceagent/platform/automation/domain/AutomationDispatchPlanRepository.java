package com.spaceagent.platform.automation.domain;

import java.util.Optional;

public interface AutomationDispatchPlanRepository {
    AutomationDispatchPlan createOrFind(AutomationDispatchPlan plan);
    Optional<AutomationDispatchPlan> findByOccurrence(String tenantId, String ownerId, String occurrenceId);
    Optional<AutomationDispatchPlan> update(AutomationDispatchPlan plan, long expectedRevision);
}
