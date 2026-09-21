package com.spaceagent.platform.identity.api;

import java.time.Instant;

/**
 * Public user identity query result.
 */
public record UserView(
        String id,
        String tenantId,
        String externalId,
        String displayName,
        Instant createdAt) {
}
