package com.spaceagent.platform.agent.api;

import java.util.List;
import java.util.Optional;

/**
 * Public API-key lifecycle boundary owned by the Agent module.
 */
public interface AgentApiKeyApplicationApi {

    CreatedAgentApiKeyView create(CreateAgentApiKeyCommand command);

    List<AgentApiKeyView> list(String tenantId, String ownerId, String agentId);

    void revoke(RevokeAgentApiKeyCommand command);

    Optional<VerifiedAgentApiKeyView> verify(String rawKey);
}
