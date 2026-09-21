package com.spaceagent.platform.agent.infrastructure.memory;

import com.spaceagent.platform.agent.domain.AgentCurrentConfiguration;
import com.spaceagent.platform.agent.domain.AgentCurrentConfigurationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentCurrentConfigurationRepository
        implements AgentCurrentConfigurationRepository {

    private final Map<String, AgentCurrentConfiguration> values = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentCurrentConfiguration> find(
            String tenantId, String ownerUserId, String agentId) {
        return Optional.ofNullable(values.get(agentId))
                .filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> ownerUserId.equals(value.ownerUserId()));
    }

    @Override
    public List<AgentCurrentConfiguration> findByTenantAndAgentIds(
            String tenantId, List<String> agentIds) {
        java.util.Set<String> selected = java.util.Set.copyOf(agentIds);
        return values.values().stream()
                .filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> selected.contains(value.agentId()))
                .toList();
    }

    @Override
    public void insert(AgentCurrentConfiguration configuration) {
        if (values.putIfAbsent(configuration.agentId(), configuration) != null) {
            throw new IllegalStateException("Agent current configuration already exists");
        }
    }

    @Override
    public synchronized Optional<AgentCurrentConfiguration> update(
            AgentCurrentConfiguration configuration, long expectedAgentRevision) {
        AgentCurrentConfiguration current = values.get(configuration.agentId());
        if (current == null
                || !current.tenantId().equals(configuration.tenantId())
                || !current.ownerUserId().equals(configuration.ownerUserId())
                || current.agentRevision() != expectedAgentRevision
                || configuration.agentRevision() != expectedAgentRevision + 1) {
            return Optional.empty();
        }
        values.put(configuration.agentId(), configuration);
        return Optional.of(configuration);
    }
}
