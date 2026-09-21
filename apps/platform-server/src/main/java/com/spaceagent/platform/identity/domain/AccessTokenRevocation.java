package com.spaceagent.platform.identity.domain;

import java.time.Instant;

/**
 * Durable access-token revocation record keyed by an opaque token digest.
 */
public record AccessTokenRevocation(
        String tokenHash,
        String userId,
        Instant expiresAt,
        Instant revokedAt) {
}
