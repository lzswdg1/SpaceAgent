package com.spaceagent.platform.agent.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for the single canonical AgentDefinition aggregate. */
public interface AgentRepository {

    Optional<AgentDefinition> findById(String id);

    Optional<AgentDefinition> findByIdForUpdate(String id);

    List<AgentDefinition> findByOwnerId(String ownerId);

    List<AgentDefinition> findByTenantAndOwnerId(String tenantId, String ownerId);

    List<AgentDefinition> findByTenantId(String tenantId);
    default List<AgentDefinition> findPage(String tenantId, String ownerId, int offset, int limit) {
        var rows = ownerId == null ? findByTenantId(tenantId) : findByTenantAndOwnerId(tenantId, ownerId);
        return rows.stream().sorted(java.util.Comparator.comparing(AgentDefinition::updatedAt).reversed().thenComparing(AgentDefinition::id))
                .skip(offset).limit(limit).toList();
    }

    Optional<AgentDefinition> findByTenantAndName(String tenantId, String name);

    void save(AgentDefinition definition);

    void replaceKnowledgeBindings(String agentId, List<String> knowledgeBaseIds);
}
