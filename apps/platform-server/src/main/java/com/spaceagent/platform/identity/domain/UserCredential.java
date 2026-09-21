package com.spaceagent.platform.identity.domain;

import java.time.Instant;

/**
 * Durable credential material owned by the identity module.
 *
 * <p>The password hash is stored separately from {@link UserIdentity} so the
 * existing tenant/user/profile aggregate does not have to expose credential
 * concerns to unrelated public APIs.
 */
public record UserCredential(
        String userId,
        String username,
        String passwordHash,
        Instant createdAt,
        Instant updatedAt) {
}
