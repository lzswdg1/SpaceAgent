package com.spaceagent.platform.agent.domain;

import java.util.Optional;
import java.util.List;

/** Persistence port for the single mutable Agent current configuration. */
public interface AgentCurrentConfigurationRepository {

    Optional<AgentCurrentConfiguration> find(
            String tenantId, String ownerUserId, String agentId);

    List<AgentCurrentConfiguration> findByTenantAndAgentIds(
            String tenantId, List<String> agentIds);

    void insert(AgentCurrentConfiguration configuration);

    Optional<AgentCurrentConfiguration> update(
            AgentCurrentConfiguration configuration, long expectedAgentRevision);
}
