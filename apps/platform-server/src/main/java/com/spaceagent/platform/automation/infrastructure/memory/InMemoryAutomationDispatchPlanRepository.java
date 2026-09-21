package com.spaceagent.platform.automation.infrastructure.memory;

import com.spaceagent.platform.automation.domain.AutomationDispatchPlan;
import com.spaceagent.platform.automation.domain.AutomationDispatchPlanRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="memory",matchIfMissing=true)
public class InMemoryAutomationDispatchPlanRepository implements AutomationDispatchPlanRepository {
    private final Map<String, AutomationDispatchPlan> values = new LinkedHashMap<>();
    @Override public synchronized AutomationDispatchPlan createOrFind(AutomationDispatchPlan plan) {
        var existing = values.values().stream().filter(value -> value.occurrenceId().equals(plan.occurrenceId())).findFirst();
        if (existing.isPresent()) return existing.orElseThrow();
        values.put(plan.id(), plan); return plan;
    }
    @Override public synchronized Optional<AutomationDispatchPlan> findByOccurrence(String tenant,String owner,String occurrence) {
        return values.values().stream().filter(value -> value.tenantId().equals(tenant)
                && value.ownerId().equals(owner) && value.occurrenceId().equals(occurrence)).findFirst();
    }
    @Override public synchronized Optional<AutomationDispatchPlan> update(AutomationDispatchPlan plan,long revision) {
        var current=values.get(plan.id());if(current==null||current.revision()!=revision)return Optional.empty();
        values.put(plan.id(),plan);return Optional.of(plan);
    }
}
