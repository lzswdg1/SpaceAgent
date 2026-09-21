package com.spaceagent.admin.command.application;

import com.spaceagent.admin.audit.application.AdminAuditService;
import com.spaceagent.admin.audit.domain.AdminAuditOutcome;
import com.spaceagent.admin.command.domain.AdminCommand;
import com.spaceagent.admin.command.domain.AdminCommandRepository;
import com.spaceagent.admin.command.domain.AdminCommandState;
import com.spaceagent.admin.security.AdminTokenMaterial;
import com.spaceagent.admin.shared.AdminApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class AdminCommandService {
    private final AdminCommandRepository repository;
    private final AdminAuditService auditService;
    private final Clock clock;

    public AdminCommandService(
            AdminCommandRepository repository,
            AdminAuditService auditService,
            Clock adminClock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = adminClock;
    }

    @Transactional
    public AdminCommand receive(
            UUID actorId,
            String operation,
            String targetType,
            String targetId,
            String idempotencyKey,
            String requestHash,
            String requestId) {
        requireText(operation, "operation");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(requestHash, "requestHash");
        String idempotencyHash = AdminTokenMaterial.sha256(idempotencyKey);
        Instant now = clock.instant();
        AdminCommand command = new AdminCommand(UUID.randomUUID(), idempotencyHash,
                operation, targetType, targetId, requestHash, AdminCommandState.RECEIVED,
                null, null, null, actorId, now, now, null);
        if (repository.insert(command)) {
            auditService.appendCommand(actorId, null, "ADMIN_COMMAND_RECEIVED", targetType, targetId,
                    null, requestId, command.id(), requestHash, AdminAuditOutcome.SUCCEEDED, null);
            return command;
        }
        AdminCommand existing = repository.findByOperationAndIdempotencyHash(operation, idempotencyHash)
                .orElseThrow(() -> new IllegalStateException("Idempotent administrator command is missing"));
        if (!existing.requestHash().equals(requestHash)) {
            throw new AdminApiException(HttpStatus.CONFLICT, "ADMIN_IDEMPOTENCY_CONFLICT",
                    "Idempotency key was already used with another administrator request");
        }
        return existing;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST,
                    "ADMIN_COMMAND_INVALID", field + " is required");
        }
    }
}
