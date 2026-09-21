package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.TenantRole;

/**
 * Public command for assigning a user to a tenant with an explicit role.
 */
public record AddTenantMembershipCommand(
        String tenantId,
        String userId,
        TenantRole role) {

    public AddTenantMembershipCommand {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(userId, "userId");
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
