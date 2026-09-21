package com.spaceagent.platform.identity.domain;

import java.time.Instant;

/**
 * Durable authorization link between a tenant and a user.
 */
public record TenantMembership(
        String tenantId,
        String userId,
        TenantRole role,
        TenantMembershipStatus status,
        Instant joinedAt,
        Instant updatedAt) {

    public boolean isActive() {
        return status == TenantMembershipStatus.ACTIVE;
    }

    public TenantMembership activate(TenantRole nextRole, Instant now) {
        return new TenantMembership(
                tenantId, userId, nextRole, TenantMembershipStatus.ACTIVE,
                joinedAt, now);
    }

    public TenantMembership changeRole(TenantRole nextRole, Instant now) {
        if (!isActive()) {
            throw new IllegalStateException("Suspended Organization membership cannot change role");
        }
        return new TenantMembership(
                tenantId, userId, nextRole, status, joinedAt, now);
    }

    public TenantMembership suspend(Instant now) {
        return new TenantMembership(
                tenantId, userId, role, TenantMembershipStatus.SUSPENDED,
                joinedAt, now);
    }
}
