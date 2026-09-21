package com.spaceagent.platform.agent.api;

import com.spaceagent.platform.agent.domain.AgentApiKeyScope;

import java.time.Instant;
import java.util.Set;

public record CreateAgentApiKeyCommand(
        String tenantId,
        String ownerId,
        String agentId,
        String name,
        Set<AgentApiKeyScope> scopes,
        Instant expiresAt) {

    public CreateAgentApiKeyCommand {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(ownerId, "ownerId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(name, "name");
        scopes = scopes == null || scopes.isEmpty()
                ? Set.of(AgentApiKeyScope.CHAT)
                : Set.copyOf(scopes);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
