package com.spaceagent.admin.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminSession(
        UUID id,
        UUID principalId,
        String refreshTokenHash,
        String csrfTokenHash,
        long credentialVersion,
        Instant authenticatedAt,
        Instant expiresAt,
        Instant revokedAt,
        String replacedByHash,
        Instant createdAt,
        Instant lastRotatedAt) {

    public boolean activeAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

}
