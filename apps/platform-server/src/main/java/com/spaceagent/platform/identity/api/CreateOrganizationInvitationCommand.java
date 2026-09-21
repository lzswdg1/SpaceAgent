package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.TenantRole;

public record CreateOrganizationInvitationCommand(
        String organizationId,
        String actorUserId,
        String email,
        TenantRole role,
        Long expiresInHours) {
}
