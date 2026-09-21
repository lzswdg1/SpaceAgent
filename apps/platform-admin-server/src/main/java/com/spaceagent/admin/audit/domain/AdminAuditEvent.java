package com.spaceagent.admin.audit.domain;

import java.time.Instant;
import java.util.UUID;

public record AdminAuditEvent(
        UUID id,
        UUID actorId,
        UUID sessionId,
        String action,
        String targetType,
        String targetId,
        String reason,
        String requestId,
        UUID commandId,
        String inputHash,
        AdminAuditOutcome outcome,
        String safeErrorCode,
        Instant occurredAt) {
}
