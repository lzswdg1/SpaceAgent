package com.spaceagent.platform.agent.api;

import java.util.List;
import java.util.Optional;

/** Public API for the single canonical AgentDefinition aggregate. */
public interface AgentApplicationApi {

    AgentDefinitionView create(CreateAgentDefinitionCommand command);

    Optional<AgentDefinitionView> findById(String agentId);

    List<AgentDefinitionView> listByOwner(String ownerId);

    List<AgentDefinitionView> listByTenantAndOwner(String tenantId, String ownerId);

    List<AgentDefinitionView> listByTenant(String tenantId);
    default List<AgentDefinitionView> page(String tenantId, String ownerId, int offset, int limit) {
        return (ownerId == null ? listByTenant(tenantId) : listByTenantAndOwner(tenantId,ownerId)).stream().skip(offset).limit(limit).toList();
    }

    AgentDefinitionView update(UpdateAgentDefinitionCommand command);

    AgentDefinitionView updateAsOrganizationOwner(UpdateAgentDefinitionCommand command);

    void archive(String ownerId, String agentId);

    void archive(String tenantId, String ownerId, String agentId);

    AgentRuntimeConfigurationView runtimeConfiguration(String tenantId, String ownerId, String agentId);
}
