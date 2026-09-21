package com.spaceagent.admin.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminMfaChallenge(
        String tokenHash,
        UUID principalId,
        Instant expiresAt,
        int attemptCount,
        Instant consumedAt,
        Instant createdAt) {

    public boolean activeAt(Instant now, int maximumAttempts) {
        return consumedAt == null && expiresAt.isAfter(now) && attemptCount < maximumAttempts;
    }
}
