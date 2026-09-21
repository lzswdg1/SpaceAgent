package com.spaceagent.admin.audit.application;

import com.spaceagent.admin.audit.domain.AdminAuditEvent;
import com.spaceagent.admin.audit.domain.AdminAuditOutcome;
import com.spaceagent.admin.audit.domain.AdminAuditRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

@Service
public class AdminAuditService {
    private final AdminAuditRepository repository;
    private final Clock clock;

    public AdminAuditService(AdminAuditRepository repository, Clock adminClock) {
        this.repository = repository;
        this.clock = adminClock;
    }

    public void append(
            UUID actorId,
            UUID sessionId,
            String action,
            String targetType,
            String targetId,
            String requestId,
            String inputHash,
            AdminAuditOutcome outcome,
            String safeErrorCode) {
        appendCommand(actorId, sessionId, action, targetType, targetId, null, requestId,
                null, inputHash, outcome, safeErrorCode);
    }

    public void appendCommand(
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
            String safeErrorCode) {
        repository.append(new AdminAuditEvent(UUID.randomUUID(), actorId, sessionId, action,
                targetType, targetId, bounded(reason, 500), requestId, commandId, inputHash, outcome,
                safeErrorCode, clock.instant()));
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(maximum, trimmed.length()));
    }
}
