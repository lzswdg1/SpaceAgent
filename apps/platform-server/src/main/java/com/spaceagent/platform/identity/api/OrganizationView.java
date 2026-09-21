package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.TenantStatus;

import java.time.Instant;

public record OrganizationView(
        String id,
        String name,
        String slug,
        String creatorUserId,
        TenantStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant deletionRequestedAt) {
}
