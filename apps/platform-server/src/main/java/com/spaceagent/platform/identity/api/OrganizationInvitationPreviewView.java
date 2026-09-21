package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.OrganizationInvitationStatus;
import com.spaceagent.platform.identity.domain.TenantRole;

import java.time.Instant;

/** Minimal public projection. It intentionally masks the invited identity. */
public record OrganizationInvitationPreviewView(
        String organizationName,
        String organizationSlug,
        String maskedEmail,
        TenantRole role,
        OrganizationInvitationStatus status,
        Instant expiresAt) {
}
