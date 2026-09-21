package com.spaceagent.admin.command.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminCommand(
        UUID id,
        String idempotencyHash,
        String operation,
        String targetType,
        String targetId,
        String requestHash,
        AdminCommandState state,
        String platformReference,
        String resultJson,
        String safeErrorCode,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
