package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpOAuthState(
        String id,
        String connectionId,
        long connectionRevision,
        String tenantId,
        String userId,
        String stateHash,
        String encryptedProviderSession,
        Instant expiresAt,
        Instant createdAt,
        Instant consumedAt) {
}
