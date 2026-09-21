package com.spaceagent.platform.agent.infrastructure.memory;

import com.spaceagent.platform.agent.domain.AgentDefinition;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.agent.domain.AgentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory adapter for the single canonical AgentDefinition repository. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentRepository implements AgentRepository {

    private final Map<String, AgentDefinition> values = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentDefinition> findById(String id) {
        return Optional.ofNullable(values.get(id))
                .filter(value -> value.status() != AgentDefinitionStatus.ARCHIVED);
    }

    @Override
    public Optional<AgentDefinition> findByIdForUpdate(String id) {
        return findById(id);
    }

    @Override
    public List<AgentDefinition> findByOwnerId(String ownerId) {
        return values.values().stream()
                .filter(value -> value.status() != AgentDefinitionStatus.ARCHIVED)
                .filter(value -> ownerId.equals(value.ownerId()))
                .toList();
    }

    @Override
    public List<AgentDefinition> findByTenantAndOwnerId(String tenantId, String ownerId) {
        return values.values().stream()
                .filter(value -> value.status() != AgentDefinitionStatus.ARCHIVED)
                .filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> ownerId.equals(value.ownerId()))
                .toList();
    }

    @Override
    public List<AgentDefinition> findByTenantId(String tenantId) {
        return values.values().stream()
                .filter(value -> value.status() != AgentDefinitionStatus.ARCHIVED)
                .filter(value -> tenantId.equals(value.tenantId()))
                .toList();
    }

    @Override
    public Optional<AgentDefinition> findByTenantAndName(String tenantId, String name) {
        return values.values().stream()
                .filter(value -> value.status() != AgentDefinitionStatus.ARCHIVED)
                .filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> name.equals(value.name()))
                .findFirst();
    }

    @Override
    public void save(AgentDefinition definition) {
        values.put(definition.id(), definition);
    }

    @Override
    public void replaceKnowledgeBindings(String agentId, List<String> knowledgeBaseIds) {
        // Current configuration is stored through its dedicated repository.
    }
}
