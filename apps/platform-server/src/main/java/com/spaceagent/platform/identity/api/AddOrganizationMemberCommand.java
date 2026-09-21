package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.TenantRole;

public record AddOrganizationMemberCommand(
        String actorUserId,
        String organizationId,
        String memberUserId,
        TenantRole role) {
}
