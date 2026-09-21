package com.spaceagent.platform.agent.domain;

import java.time.Instant;
import java.util.Set;

/**
 * Durable API-key metadata. keyHash is the only persisted credential material.
 */
public record AgentApiKey(
        String id,
        String agentId,
        String name,
        String keyHash,
        String keyPrefix,
        Set<AgentApiKeyScope> scopes,
        boolean enabled,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt) {

    public AgentApiKey {
        scopes = scopes == null ? Set.of(AgentApiKeyScope.CHAT) : Set.copyOf(scopes);
    }

    public boolean isValid(Instant now) {
        return enabled && revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
