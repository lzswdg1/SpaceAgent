package com.spaceagent.platform.agent.infrastructure.memory;

import com.spaceagent.platform.agent.domain.AgentApiKey;
import com.spaceagent.platform.agent.domain.AgentApiKeyRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentApiKeyRepository implements AgentApiKeyRepository {

    private final Map<String, AgentApiKey> values = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentApiKey> findByHash(String keyHash) {
        return values.values().stream().filter(key -> keyHash.equals(key.keyHash())).findFirst();
    }

    @Override
    public Optional<AgentApiKey> findById(String keyId) {
        return Optional.ofNullable(values.get(keyId));
    }

    @Override
    public List<AgentApiKey> findByAgentId(String agentId) {
        return values.values().stream()
                .filter(key -> agentId.equals(key.agentId()))
                .toList();
    }

    @Override
    public void save(AgentApiKey apiKey) {
        values.put(apiKey.id(), apiKey);
    }

    @Override
    public boolean revoke(String agentId, String keyId, Instant revokedAt) {
        AgentApiKey current = values.get(keyId);
        if (current == null || !agentId.equals(current.agentId()) || current.revokedAt() != null) {
            return false;
        }
        values.put(keyId, new AgentApiKey(
                current.id(), current.agentId(), current.name(), current.keyHash(), current.keyPrefix(),
                current.scopes(), false, current.createdAt(), current.lastUsedAt(), current.expiresAt(), revokedAt));
        return true;
    }

    @Override
    public void markUsed(String keyId, Instant usedAt) {
        values.computeIfPresent(keyId, (id, current) -> new AgentApiKey(
                current.id(), current.agentId(), current.name(), current.keyHash(), current.keyPrefix(),
                current.scopes(), current.enabled(), current.createdAt(), usedAt,
                current.expiresAt(), current.revokedAt()));
    }
}
