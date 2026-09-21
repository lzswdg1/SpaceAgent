package com.spaceagent.platform.tooling.infrastructure.memory;

import com.spaceagent.platform.tooling.domain.McpOAuthState;
import com.spaceagent.platform.tooling.domain.McpOAuthStateRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryMcpOAuthStateRepository implements McpOAuthStateRepository {
    private final Map<String, McpOAuthState> states = new ConcurrentHashMap<>();

    @Override
    public void save(McpOAuthState state) {
        states.put(state.stateHash(), state);
    }

    @Override
    public synchronized Optional<McpOAuthState> consume(
            String stateHash, String tenantId, String userId, Instant now) {
        McpOAuthState state = states.get(stateHash);
        if (state == null || state.consumedAt() != null
                || !state.tenantId().equals(tenantId) || !state.userId().equals(userId)
                || !state.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        McpOAuthState consumed = new McpOAuthState(
                state.id(), state.connectionId(), state.connectionRevision(), state.tenantId(),
                state.userId(), state.stateHash(), state.encryptedProviderSession(),
                state.expiresAt(), state.createdAt(), now);
        states.put(stateHash, consumed);
        return Optional.of(consumed);
    }

    @Override
    public synchronized void redact(String id, Instant now) {
        states.replaceAll((hash, state) -> state.id().equals(id) && state.consumedAt() != null
                ? new McpOAuthState(
                        state.id(), state.connectionId(), state.connectionRevision(),
                        state.tenantId(), state.userId(), state.stateHash(), "REDACTED",
                        state.expiresAt(), state.createdAt(), state.consumedAt())
                : state);
    }
}
