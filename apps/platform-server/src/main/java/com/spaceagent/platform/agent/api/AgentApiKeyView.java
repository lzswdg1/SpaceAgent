package com.spaceagent.platform.agent.api;

import com.spaceagent.platform.agent.domain.AgentApiKeyScope;

import java.time.Instant;
import java.util.Set;

/**
 * Safe API-key metadata. The persisted hash is never exposed.
 */
public record AgentApiKeyView(
        String id,
        String agentId,
        String name,
        String keyPrefix,
        Set<AgentApiKeyScope> scopes,
        boolean enabled,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt) {
}
