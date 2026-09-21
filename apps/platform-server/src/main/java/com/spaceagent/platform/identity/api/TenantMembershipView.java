package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;

import java.time.Instant;

/**
 * Public tenant membership and authorization view.
 */
public record TenantMembershipView(
        String tenantId,
        String userId,
        TenantRole role,
        TenantMembershipStatus status,
        Instant joinedAt,
        Instant updatedAt) {
}
