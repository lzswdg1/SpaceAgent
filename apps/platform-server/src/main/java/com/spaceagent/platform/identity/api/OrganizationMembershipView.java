package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;

import java.time.Instant;

public record OrganizationMembershipView(
        String organizationId,
        String userId,
        TenantRole role,
        TenantMembershipStatus status,
        Instant joinedAt,
        Instant updatedAt) {
}
