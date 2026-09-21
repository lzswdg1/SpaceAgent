package com.spaceagent.platform.identity.domain;

import java.time.Instant;

/**
 * A durable user identity scoped to a tenant.
 */
public record UserIdentity(
        String id,
        String tenantId,
        String externalId,
        String displayName,
        Instant createdAt,
        Instant updatedAt) {
}
