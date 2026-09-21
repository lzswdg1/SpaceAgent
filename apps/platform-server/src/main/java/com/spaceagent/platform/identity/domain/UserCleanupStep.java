package com.spaceagent.platform.identity.domain;

import java.time.Instant;

public record UserCleanupStep(
        String userId,
        UserCleanupStepKey stepKey,
        int sequence,
        UserCleanupStepState state,
        int attempt,
        String lastErrorCode,
        String lastErrorSummary,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
