package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.OrganizationInvitationStatus;
import com.spaceagent.platform.identity.domain.TenantRole;

import java.time.Instant;

public record OrganizationInvitationView(
        String id,
        String organizationId,
        String email,
        TenantRole role,
        OrganizationInvitationStatus status,
        String invitedByUserId,
        Instant expiresAt,
        String acceptedByUserId,
        Instant createdAt,
        Instant updatedAt,
        Instant acceptedAt,
        Instant revokedAt) {
}
