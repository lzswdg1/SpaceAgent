package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.AgentDelegation;
import com.spaceagent.platform.runtime.domain.AgentDelegationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentDelegationRepository implements AgentDelegationRepository {

    private final Map<String, AgentDelegation> values = new ConcurrentHashMap<>();

    @Override
    public void save(AgentDelegation delegation) {
        values.put(delegation.id(), delegation);
    }

    @Override
    public Optional<AgentDelegation> findById(String id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public List<AgentDelegation> findByParentRunId(String parentRunId) {
        return values.values().stream()
                .filter(delegation -> delegation.parentRunId().equals(parentRunId))
                .sorted(Comparator.comparing(AgentDelegation::createdAt).thenComparing(AgentDelegation::id))
                .toList();
    }
}
