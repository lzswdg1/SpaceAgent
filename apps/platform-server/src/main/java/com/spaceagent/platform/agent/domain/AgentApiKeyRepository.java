package com.spaceagent.platform.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentApiKeyRepository {

    Optional<AgentApiKey> findByHash(String keyHash);

    Optional<AgentApiKey> findById(String keyId);

    List<AgentApiKey> findByAgentId(String agentId);

    void save(AgentApiKey apiKey);

    boolean revoke(String agentId, String keyId, Instant revokedAt);

    void markUsed(String keyId, Instant usedAt);
}
