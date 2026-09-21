package com.spaceagent.admin.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.admin.audit.application.AdminAuditService;
import com.spaceagent.admin.audit.domain.AdminAuditOutcome;
import com.spaceagent.admin.command.application.AdminCommandService;
import com.spaceagent.admin.command.domain.AdminCommand;
import com.spaceagent.admin.command.domain.AdminCommandRepository;
import com.spaceagent.admin.command.domain.AdminCommandState;
import com.spaceagent.admin.identity.domain.AdminIdentityRepository;
import com.spaceagent.admin.identity.domain.AdminPrincipalStatus;
import com.spaceagent.admin.identity.domain.AdminSession;
import com.spaceagent.admin.identity.domain.SystemAdministrator;
import com.spaceagent.admin.security.AdminTokenMaterial;
import com.spaceagent.admin.shared.AdminApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminPrincipalAdministrationService {

    private final AdminIdentityRepository identities;
    private final AdminAuthenticationService authentication;
    private final AdminCommandService commands;
    private final AdminCommandRepository commandRepository;
    private final AdminAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminPrincipalAdministrationService(
            AdminIdentityRepository identities,
            AdminAuthenticationService authentication,
            AdminCommandService commands,
            AdminCommandRepository commandRepository,
            AdminAuditService audit,
            ObjectMapper objectMapper,
            Clock adminClock) {
        this.identities = identities;
        this.authentication = authentication;
        this.commands = commands;
        this.commandRepository = commandRepository;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = adminClock;
    }

    @Transactional
    public AdministratorPage administrators(
            int page, int pageSize, String query, String status,
            UUID actor, UUID sessionId, String requestId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        String safeQuery = bounded(query, 120);
        AdminPrincipalStatus safeStatus = status(status);
        var rows = identities.pagePrincipals(safePage * safeSize, safeSize, safeQuery, safeStatus);
        audit.append(actor, sessionId, "ADMIN_PRINCIPALS_READ", "ADMIN_PRINCIPAL", null,
                requestId, AdminTokenMaterial.sha256("principals:" + safePage + ":" + safeSize
                        + ":" + safeQuery + ":" + safeStatus), AdminAuditOutcome.SUCCEEDED, null);
        return new AdministratorPage(rows.items().stream().map(this::view).toList(), safePage,
                safeSize, rows.total(), clock.instant());
    }

    @Transactional
    public SessionPage sessions(
            UUID principalId, int page, int pageSize, UUID currentSession,
            UUID actor, String requestId) {
        if (!actor.equals(principalId)) throw singletonOnly();
        requirePrincipal(principalId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = identities.pageSessions(principalId, safePage * safeSize, safeSize);
        audit.append(actor, currentSession, "ADMIN_PRINCIPAL_SESSIONS_READ", "ADMIN_PRINCIPAL",
                principalId.toString(), requestId,
                AdminTokenMaterial.sha256("sessions:" + principalId + ":" + safePage + ":" + safeSize),
                AdminAuditOutcome.SUCCEEDED, null);
        Instant now = clock.instant();
        return new SessionPage(rows.items().stream().map(value -> sessionView(value, currentSession, now))
                .toList(), safePage, safeSize, rows.total(), now);
    }

    @Transactional
    public PrincipalCommandResult create(
            UUID actor, UUID sessionId, String idempotencyKey,
            String loginName, String displayName, String reason, String requestId) {
        authentication.requireActiveSession(actor, sessionId);
        throw singletonOnly();
    }

    @Transactional
    public PrincipalCommandResult suspend(
            UUID actor, UUID sessionId, UUID targetId, String idempotencyKey,
            String reason, String requestId) {
        authentication.requireActiveSession(actor, sessionId);
        throw singletonOnly();
    }

    @Transactional
    public PrincipalCommandResult restore(
            UUID actor, UUID sessionId, UUID targetId, String idempotencyKey,
            String reason, String requestId) {
        authentication.requireActiveSession(actor, sessionId);
        throw singletonOnly();
    }

    @Transactional
    public PrincipalCommandResult resetCredentials(
            UUID actor, UUID sessionId, UUID targetId, String idempotencyKey,
            String reason, String requestId) {
        authentication.requireActiveSession(actor, sessionId);
        throw singletonOnly();
    }

    @Transactional
    public PrincipalCommandResult revokeSession(
            UUID actor, UUID currentSession, UUID principalId, UUID targetSession,
            String idempotencyKey, String reason, String requestId) {
        authentication.requireActiveSession(actor, currentSession);
        if (!actor.equals(principalId)) throw singletonOnly();
        if (currentSession.equals(targetSession)) throw conflict("ADMIN_CURRENT_SESSION_PROTECTED");
        requireReason(reason);
        AdminSession session = identities.findSessionById(targetSession)
                .filter(value -> value.principalId().equals(principalId))
                .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND,
                        "ADMIN_SESSION_NOT_FOUND", "Administrator session was not found"));
        AdminCommand command = receive(actor, "ADMIN_PRINCIPAL_SESSION_REVOKE", principalId,
                idempotencyKey, reason, Map.of("sessionId", targetSession.toString()), requestId);
        if (command.state() == AdminCommandState.SUCCEEDED) return result(command, null, 0);
        if (!claim(command)) return result(commandRepository.findById(command.id()).orElseThrow(), null, 0);
        long revoked = identities.revokeSession(session.id(), null, clock.instant()) ? 1 : 0;
        complete(command, principalId, requirePrincipal(principalId).status().name(), actor,
                currentSession, reason, requestId);
        return result(commandRepository.findById(command.id()).orElseThrow(), null, revoked);
    }

    private AdminCommand receive(UUID actor, String operation, UUID targetId, String idempotencyKey,
                                 String reason, Map<String, String> input, String requestId) {
        String canonical = operation + "|" + targetId + "|" + reason.trim()
                + "|" + new java.util.TreeMap<>(input);
        return commands.receive(actor, operation, "ADMIN_PRINCIPAL", targetId.toString(),
                required(idempotencyKey, "Idempotency-Key", 200),
                AdminTokenMaterial.sha256(canonical), requestId);
    }

    private boolean claim(AdminCommand command) {
        if (command.state() != AdminCommandState.RECEIVED) return false;
        return commandRepository.transition(command.id(), AdminCommandState.RECEIVED,
                AdminCommandState.DISPATCHING, command.id().toString(), null, null,
                clock.instant(), false);
    }

    private void complete(AdminCommand command, UUID principalId, String status,
                          UUID actor, UUID sessionId, String reason, String requestId) {
        String result;
        try {
            result = objectMapper.writeValueAsString(Map.of(
                    "principalId", principalId.toString(), "status", status));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize administrator command evidence", error);
        }
        if (!commandRepository.transition(command.id(), AdminCommandState.DISPATCHING,
                AdminCommandState.SUCCEEDED, principalId.toString(), result, null,
                clock.instant(), true)) {
            throw new IllegalStateException("Unable to complete administrator command");
        }
        audit.appendCommand(actor, sessionId, command.operation(), "ADMIN_PRINCIPAL",
                principalId.toString(), reason, requestId, command.id(), command.requestHash(),
                AdminAuditOutcome.SUCCEEDED, null);
    }

    private PrincipalCommandResult result(
            AdminCommand command, ProvisioningMaterial material, long revokedSessions) {
        UUID principalId = UUID.fromString(command.targetId());
        return new PrincipalCommandResult(command.id(), command.operation(), command.state().name(),
                administrator(principalId), material, revokedSessions,
                command.createdAt(), command.updatedAt(), command.completedAt());
    }

    private AdministratorView administrator(UUID principalId) {
        return identities.pagePrincipals(0, 1, principalId.toString(), null).items().stream()
                .filter(value -> value.principal().id().equals(principalId)).findFirst()
                .map(this::view).orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND,
                        "ADMIN_PRINCIPAL_NOT_FOUND", "Administrator was not found"));
    }

    private SystemAdministrator requirePrincipal(UUID id) {
        return identities.findPrincipalById(id).orElseThrow(() -> new AdminApiException(
                HttpStatus.NOT_FOUND, "ADMIN_PRINCIPAL_NOT_FOUND", "Administrator was not found"));
    }

    private AdministratorView view(AdminIdentityRepository.PrincipalRow row) {
        SystemAdministrator value = row.principal();
        return new AdministratorView(value.id(), value.loginName(), value.displayName(),
                value.status().name(), value.role().name(), value.mustChangePassword(),
                value.credentialVersion(), value.lastSuccessfulLoginAt(), value.createdAt(),
                value.updatedAt(), row.activeSessions(), row.remainingRecoveryCodes());
    }

    private static SessionView sessionView(AdminSession value, UUID currentSession, Instant now) {
        return new SessionView(value.id(), value.principalId(), value.activeAt(now),
                value.id().equals(currentSession), value.credentialVersion(), value.authenticatedAt(),
                value.expiresAt(), value.revokedAt(), value.createdAt(), value.lastRotatedAt());
    }

    private static AdminPrincipalStatus status(String value) {
        if (value == null || value.isBlank()) return null;
        try { return AdminPrincipalStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "ADMIN_PRINCIPAL_STATUS_INVALID",
                    "Administrator status is invalid");
        }
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(maximum, trimmed.length()));
    }

    private static String required(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "ADMIN_COMMAND_INVALID",
                    name + " is invalid");
        }
        return value.trim();
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "ADMIN_REASON_REQUIRED",
                    "Administrator reason is required");
        }
    }

    private static AdminApiException conflict(String code) {
        return new AdminApiException(HttpStatus.CONFLICT, code,
                "Administrator lifecycle transition is not allowed");
    }

    private static AdminApiException singletonOnly() {
        return new AdminApiException(HttpStatus.CONFLICT,
                "ADMIN_SINGLETON_PRINCIPAL_ENFORCED",
                "The platform supports exactly one SystemAdministrator");
    }

    public record AdministratorPage(List<AdministratorView> items, int page, int pageSize,
                                    long total, Instant generatedAt) {
        public AdministratorPage { items = items == null ? List.of() : List.copyOf(items); }
    }

    public record AdministratorView(
            UUID id, String loginName, String displayName, String status, String role,
            boolean mustChangePassword, long credentialVersion, Instant lastSuccessfulLoginAt,
            Instant createdAt, Instant updatedAt, long activeSessions,
            long remainingRecoveryCodes) {
    }

    public record SessionPage(List<SessionView> items, int page, int pageSize,
                              long total, Instant generatedAt) {
        public SessionPage { items = items == null ? List.of() : List.copyOf(items); }
    }

    public record SessionView(
            UUID id, UUID principalId, boolean active, boolean current,
            long credentialVersion, Instant authenticatedAt, Instant expiresAt,
            Instant revokedAt, Instant createdAt, Instant lastRotatedAt) {
    }

    public record ProvisioningMaterial(
            String temporaryPassword, String totpSecret, List<String> recoveryCodes) {
        public ProvisioningMaterial {
            recoveryCodes = recoveryCodes == null ? List.of() : List.copyOf(recoveryCodes);
        }
    }

    public record PrincipalCommandResult(
            UUID commandId, String operation, String state, AdministratorView administrator,
            ProvisioningMaterial provisioning, long revokedSessions,
            Instant createdAt, Instant updatedAt, Instant completedAt) {
    }
}
