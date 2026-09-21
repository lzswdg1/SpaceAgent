package com.spaceagent.admin.audit.application;

import com.spaceagent.admin.audit.domain.AdminAuditEvent;
import com.spaceagent.admin.audit.domain.AdminAuditOutcome;
import com.spaceagent.admin.audit.domain.AdminAuditRepository;
import com.spaceagent.admin.command.domain.AdminCommand;
import com.spaceagent.admin.command.domain.AdminCommandRepository;
import com.spaceagent.admin.security.AdminTokenMaterial;
import com.spaceagent.admin.shared.AdminApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AdminEvidenceReadService {
    private final AdminAuditRepository auditRepository;
    private final AdminCommandRepository commandRepository;
    private final AdminAuditService auditService;
    private final Clock clock;

    public AdminEvidenceReadService(AdminAuditRepository auditRepository,
                                    AdminCommandRepository commandRepository,
                                    AdminAuditService auditService,
                                    Clock adminClock) {
        this.auditRepository = auditRepository;
        this.commandRepository = commandRepository;
        this.auditService = auditService;
        this.clock = adminClock;
    }

    @Transactional
    public AuditPage auditEvents(int page, int pageSize, UUID actorFilter, String action,
                                 String targetId, UUID reader, UUID sessionId, String requestId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        String safeAction = bounded(action, 100);
        String safeTarget = bounded(targetId, 160);
        var result = auditRepository.page(safePage * safeSize, safeSize, actorFilter,
                safeAction, safeTarget);
        auditService.append(reader, sessionId, "ADMIN_AUDIT_READ", "ADMIN_AUDIT", null,
                requestId, AdminTokenMaterial.sha256("audit:" + safePage + ":" + safeSize
                        + ":" + actorFilter + ":" + safeAction + ":" + safeTarget),
                AdminAuditOutcome.SUCCEEDED, null);
        return new AuditPage(result.items().stream().map(AuditView::from).toList(), safePage,
                safeSize, result.total(), clock.instant());
    }

    @Transactional
    public CommandView command(UUID commandId, UUID reader, UUID sessionId, String requestId) {
        AdminCommand command = commandRepository.findById(commandId).orElseThrow(() ->
                new AdminApiException(HttpStatus.NOT_FOUND, "ADMIN_COMMAND_NOT_FOUND",
                        "Administrator command was not found"));
        auditService.append(reader, sessionId, "ADMIN_COMMAND_READ", "ADMIN_COMMAND",
                commandId.toString(), requestId, AdminTokenMaterial.sha256(commandId.toString()),
                AdminAuditOutcome.SUCCEEDED, null);
        return CommandView.from(command);
    }

    @Transactional
    public CommandPage commands(int page, int pageSize, String state, String operation,
                                String targetId, UUID reader, UUID sessionId, String requestId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        String safeState = commandState(state);
        String safeOperation = bounded(operation, 80);
        String safeTarget = bounded(targetId, 160);
        var result = commandRepository.page(safePage * safeSize, safeSize, safeState,
                safeOperation, safeTarget);
        auditService.append(reader, sessionId, "ADMIN_COMMANDS_READ", "ADMIN_COMMAND", null,
                requestId, AdminTokenMaterial.sha256("commands:" + safePage + ":" + safeSize
                        + ":" + safeState + ":" + safeOperation + ":" + safeTarget),
                AdminAuditOutcome.SUCCEEDED, null);
        return new CommandPage(result.items().stream().map(CommandView::from).toList(), safePage,
                safeSize, result.total(), clock.instant());
    }

    private static String commandState(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return java.util.Set.of("RECEIVED", "DISPATCHING", "ACCEPTED", "SUCCEEDED", "FAILED", "UNKNOWN")
                .contains(normalized) ? normalized : null;
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(maximum, trimmed.length()));
    }

    public record AuditPage(List<AuditView> items, int page, int pageSize, long total,
                            Instant generatedAt) {
        public AuditPage { items = items == null ? List.of() : List.copyOf(items); }
    }

    public record AuditView(UUID id, UUID actorId, UUID sessionId, String action,
                            String targetType, String targetId, String requestId, UUID commandId,
                            String inputHash, String outcome, String safeErrorCode, Instant occurredAt) {
        static AuditView from(AdminAuditEvent event) {
            return new AuditView(event.id(), event.actorId(), event.sessionId(), event.action(),
                    event.targetType(), event.targetId(), event.requestId(), event.commandId(),
                    event.inputHash(), event.outcome().name(), event.safeErrorCode(), event.occurredAt());
        }
    }

    public record CommandView(UUID id, String operation, String targetType, String targetId,
                              String state, String platformReference, String safeErrorCode,
                              UUID createdBy, Instant createdAt, Instant updatedAt, Instant completedAt) {
        static CommandView from(AdminCommand command) {
            return new CommandView(command.id(), command.operation(), command.targetType(),
                    command.targetId(), command.state().name(), command.platformReference(),
                    command.safeErrorCode(), command.createdBy(), command.createdAt(),
                    command.updatedAt(), command.completedAt());
        }
    }

    public record CommandPage(List<CommandView> items, int page, int pageSize, long total,
                              Instant generatedAt) {
        public CommandPage { items = items == null ? List.of() : List.copyOf(items); }
    }
}
