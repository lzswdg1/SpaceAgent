package com.spaceagent.platform.agent.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for temporary Organization Agent configuration proposals. */
public interface AgentConfigurationChangeRequestRepository {

    Optional<AgentConfigurationChangeRequest> findById(String tenantId, String requestId);

    Optional<AgentConfigurationChangeRequest> findByIdForUpdate(String tenantId, String requestId);

    Optional<AgentConfigurationChangeRequest> findPending(
            String tenantId, String agentId, String requestedBy);

    List<AgentConfigurationChangeRequest> listByAgent(
            String tenantId, String agentId, int offset, int limit);

    void insert(AgentConfigurationChangeRequest request);

    Optional<AgentConfigurationChangeRequest> update(
            AgentConfigurationChangeRequest request,
            long expectedRevision,
            AgentConfigurationChangeState expectedState);
}
