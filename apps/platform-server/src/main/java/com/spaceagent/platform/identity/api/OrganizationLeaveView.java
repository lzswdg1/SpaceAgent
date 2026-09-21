package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.TenantStatus;

public record OrganizationLeaveView(
        String organizationId,
        TenantStatus organizationStatus,
        long remainingActiveMembers) {
}
