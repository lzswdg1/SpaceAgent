package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.TenantStatus;

import java.time.Instant;

/**
 * Public tenant query result.
 */
public record TenantView(
        String id,
        String name,
        String slug,
        TenantStatus status,
        Instant createdAt) {
}
