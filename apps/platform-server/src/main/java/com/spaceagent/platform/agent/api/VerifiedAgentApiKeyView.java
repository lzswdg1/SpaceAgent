package com.spaceagent.platform.agent.api;

import com.spaceagent.platform.agent.domain.AgentApiKeyScope;

import java.time.Instant;
import java.util.Set;

public record VerifiedAgentApiKeyView(
        String keyId,
        String agentId,
        String ownerUserId,
        String tenantId,
        Set<AgentApiKeyScope> scopes,
        Instant expiresAt) {
}
