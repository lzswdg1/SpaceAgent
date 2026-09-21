package com.spaceagent.platform.identity.domain;

import java.time.Instant;

public record OrganizationCleanupStep(
        String organizationId,
        OrganizationCleanupStepKey stepKey,
        int sequence,
        OrganizationCleanupStepState state,
        int attempt,
        String lastErrorCode,
        String lastErrorSummary,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
