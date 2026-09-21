package com.spaceagent.admin.command.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.admin.audit.application.AdminAuditService;
import com.spaceagent.admin.audit.domain.AdminAuditOutcome;
import com.spaceagent.admin.command.domain.AdminCommand;
import com.spaceagent.admin.command.domain.AdminCommandRepository;
import com.spaceagent.admin.command.domain.AdminCommandState;
import com.spaceagent.admin.identity.application.AdminAuthenticationService;
import com.spaceagent.admin.platformclient.AdminPlatformClient;
import com.spaceagent.admin.platformclient.AdminPlatformClientException;
import com.spaceagent.admin.platformclient.PlatformAdminWire;
import com.spaceagent.admin.security.AdminTokenMaterial;
import com.spaceagent.admin.shared.AdminApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminCommandDispatchService {
    private final AdminAuthenticationService authentication;
    private final AdminCommandService commandService;
    private final AdminCommandRepository repository;
    private final AdminPlatformClient platformClient;
    private final AdminAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminCommandDispatchService(
            AdminAuthenticationService authentication,
            AdminCommandService commandService,
            AdminCommandRepository repository,
            AdminPlatformClient platformClient,
            AdminAuditService audit,
            ObjectMapper objectMapper,
            Clock adminClock) {
        this.authentication = authentication;
        this.commandService = commandService;
        this.repository = repository;
        this.platformClient = platformClient;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = adminClock;
    }

    public CommandResult createUser(UUID actor, UUID sessionId, String idempotencyKey,
                                    String loginName, String displayName, String organizationName,
                                    String organizationSlug, String reason, String requestId) {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("loginName", required(loginName, "loginName"));
        put(input, "displayName", displayName);
        put(input, "organizationName", organizationName);
        put(input, "organizationSlug", organizationSlug);
        return dispatch(actor, sessionId, idempotencyKey, "USER_CREATE", "PLATFORM_USER", null,
                reason, input,
                requestId, command -> platformClient.createUser(command.id(), idempotencyKey,
                        actor.toString(), requestId, loginName, displayName, organizationName,
                        organizationSlug, reason));
    }

    public CommandResult updateUser(UUID actor, UUID sessionId, String idempotencyKey,
            String userId, String displayName, String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "USER_UPDATE", "PLATFORM_USER", userId, reason,
                Map.of("displayName", required(displayName, "displayName")), requestId,
                command -> platformClient.updateUser(command.id(), idempotencyKey, actor.toString(),
                        requestId, userId, displayName, reason));
    }

    public CommandResult revokeUserSessions(UUID actor, UUID sessionId, String idempotencyKey,
            String userId, String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "USER_SESSIONS_REVOKE", "PLATFORM_USER", userId,
                reason, Map.of(), requestId, command -> platformClient.revokeUserSessions(command.id(),
                        idempotencyKey, actor.toString(), requestId, userId, reason));
    }

    public CommandResult resetUserPassword(UUID actor, UUID sessionId, String idempotencyKey,
            String userId, String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "USER_PASSWORD_RESET", "PLATFORM_USER", userId,
                reason, Map.of(), requestId, command -> platformClient.resetUserPassword(command.id(),
                        idempotencyKey, actor.toString(), requestId, userId, reason));
    }

    public CommandResult suspendUser(UUID actor, UUID sessionId, String idempotencyKey,
                                     String userId, String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "USER_SUSPEND", "PLATFORM_USER",
                userId, reason,
                Map.of(), requestId, command -> platformClient.suspendUser(command.id(),
                        idempotencyKey, actor.toString(), requestId, userId, reason));
    }

    public CommandResult restoreUser(UUID actor, UUID sessionId, String idempotencyKey,
                                     String userId, String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "USER_RESTORE", "PLATFORM_USER",
                userId, reason,
                Map.of(), requestId, command -> platformClient.restoreUser(command.id(),
                        idempotencyKey, actor.toString(), requestId, userId, reason));
    }

    public CommandResult deletionPreflight(UUID actor, UUID sessionId, String idempotencyKey,
                                           String userId, String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "USER_DELETION_PREFLIGHT",
                "PLATFORM_USER", userId, reason, Map.of(), requestId,
                command -> platformClient.deletionPreflight(
                command.id(), idempotencyKey, actor.toString(), requestId, userId, reason));
    }

    public CommandResult deleteUser(UUID actor, UUID sessionId, String idempotencyKey,
                                    String userId, String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "USER_DELETION_REQUEST",
                "PLATFORM_USER", userId, reason, Map.of(), requestId,
                command -> platformClient.requestDeletion(
                        command.id(), idempotencyKey, actor.toString(), requestId, userId, reason));
    }

    public CommandResult createOrganization(
            UUID actor, UUID sessionId, String idempotencyKey, String ownerUserId,
            String name, String slug, String reason, String requestId) {
        Map<String, String> input = Map.of("ownerUserId", required(ownerUserId, "ownerUserId"),
                "name", required(name, "name"), "slug", required(slug, "slug"));
        return dispatch(actor, sessionId, idempotencyKey, "ORGANIZATION_CREATE", "ORGANIZATION",
                null, reason, input, requestId,
                command -> platformClient.createOrganization(command.id(), idempotencyKey,
                        actor.toString(), requestId, ownerUserId, name, slug, reason));
    }

    public CommandResult updateOrganization(
            UUID actor, UUID sessionId, String idempotencyKey, String organizationId,
            String name, String slug, String reason, String requestId) {
        Map<String, String> input = Map.of("name", required(name, "name"),
                "slug", required(slug, "slug"));
        return dispatch(actor, sessionId, idempotencyKey, "ORGANIZATION_UPDATE", "ORGANIZATION",
                required(organizationId, "organizationId"), reason, input, requestId,
                command -> platformClient.updateOrganization(command.id(), idempotencyKey,
                        actor.toString(), requestId, organizationId, name, slug, reason));
    }

    public CommandResult deleteOrganization(
            UUID actor, UUID sessionId, String idempotencyKey, String organizationId,
            String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "ORGANIZATION_DELETE", "ORGANIZATION",
                required(organizationId, "organizationId"), reason, Map.of(), requestId,
                command -> platformClient.deleteOrganization(command.id(), idempotencyKey,
                        actor.toString(), requestId, organizationId, reason));
    }

    public CommandResult addOrganizationMember(
            UUID actor, UUID sessionId, String idempotencyKey, String organizationId,
            String userId, String role, String reason, String requestId) {
        Map<String, String> input = Map.of("userId", required(userId, "userId"),
                "role", required(role, "role"));
        return dispatch(actor, sessionId, idempotencyKey, "ORGANIZATION_MEMBER_ADD",
                "ORGANIZATION", required(organizationId, "organizationId"), reason, input,
                requestId, command -> platformClient.addOrganizationMember(command.id(),
                        idempotencyKey, actor.toString(), requestId, organizationId, userId,
                        role, reason));
    }

    public CommandResult updateOrganizationMemberRole(
            UUID actor, UUID sessionId, String idempotencyKey, String organizationId,
            String userId, String role, String reason, String requestId) {
        Map<String, String> input = Map.of("userId", required(userId, "userId"),
                "role", required(role, "role"));
        return dispatch(actor, sessionId, idempotencyKey, "ORGANIZATION_MEMBER_ROLE_UPDATE",
                "ORGANIZATION", required(organizationId, "organizationId"), reason, input,
                requestId, command -> platformClient.updateOrganizationMemberRole(command.id(),
                        idempotencyKey, actor.toString(), requestId, organizationId, userId,
                        role, reason));
    }

    public CommandResult removeOrganizationMember(
            UUID actor, UUID sessionId, String idempotencyKey, String organizationId,
            String userId, String reason, String requestId) {
        Map<String, String> input = Map.of("userId", required(userId, "userId"));
        return dispatch(actor, sessionId, idempotencyKey, "ORGANIZATION_MEMBER_REMOVE",
                "ORGANIZATION", required(organizationId, "organizationId"), reason, input,
                requestId, command -> platformClient.removeOrganizationMember(command.id(),
                        idempotencyKey, actor.toString(), requestId, organizationId, userId,
                        reason));
    }

    public CommandResult transferOrganizationOwnership(
            UUID actor, UUID sessionId, String idempotencyKey, String organizationId,
            String newOwnerUserId, String reason, String requestId) {
        Map<String, String> input = Map.of(
                "newOwnerUserId", required(newOwnerUserId, "newOwnerUserId"));
        return dispatch(actor, sessionId, idempotencyKey, "ORGANIZATION_OWNER_TRANSFER",
                "ORGANIZATION", required(organizationId, "organizationId"), reason, input,
                requestId, command -> platformClient.transferOrganizationOwnership(command.id(),
                        idempotencyKey, actor.toString(), requestId, organizationId,
                        newOwnerUserId, reason));
    }

    public CommandResult requestMcpRegistrySync(
            UUID actor, UUID sessionId, String idempotencyKey,
            String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "MCP_REGISTRY_SYNC_REQUEST",
                "MCP_REGISTRY", null, reason, Map.of(), requestId,
                command -> platformClient.requestMcpRegistrySync(command.id(), idempotencyKey,
                        actor.toString(), requestId, reason));
    }

    public CommandResult approveMcpRegistryCandidate(
            UUID actor, UUID sessionId, String idempotencyKey, String candidateId,
            String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "MCP_REGISTRY_CANDIDATE_APPROVE",
                "MCP_REGISTRY_CANDIDATE", required(candidateId, "candidateId"), reason,
                Map.of(), requestId, command -> platformClient.approveMcpRegistryCandidate(
                        command.id(), idempotencyKey, actor.toString(), requestId,
                        candidateId, reason));
    }

    public CommandResult rejectMcpRegistryCandidate(
            UUID actor, UUID sessionId, String idempotencyKey, String candidateId,
            String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "MCP_REGISTRY_CANDIDATE_REJECT",
                "MCP_REGISTRY_CANDIDATE", required(candidateId, "candidateId"), reason,
                Map.of(), requestId, command -> platformClient.rejectMcpRegistryCandidate(
                        command.id(), idempotencyKey, actor.toString(), requestId,
                        candidateId, reason));
    }

    public CommandResult retryArtifactDeletion(
            UUID actor, UUID sessionId, String idempotencyKey,
            String objectId, String tenantId, String reason, String requestId) {
        return dispatch(actor, sessionId, idempotencyKey, "ARTIFACT_DELETION_RETRY",
                "ARTIFACT_OBJECT", required(objectId, "objectId"), reason,
                Map.of("tenantId", required(tenantId, "tenantId")), requestId,
                command -> platformClient.retryArtifactDeletion(
                        command.id(), idempotencyKey, actor.toString(), requestId,
                        objectId, tenantId, reason));
    }

    public PlatformAdminWire.UserCleanupJobProjection userCleanupJob(
            UUID actor, UUID sessionId, String userId, String requestId) {
        authentication.requireActiveSession(actor, sessionId);
        try {
            var result = platformClient.userCleanupJob(userId, actor.toString(), requestId);
            audit.append(actor, sessionId, "ADMIN_USER_CLEANUP_READ", "PLATFORM_USER", userId,
                    requestId, AdminTokenMaterial.sha256(userId), AdminAuditOutcome.SUCCEEDED, null);
            return result;
        } catch (AdminPlatformClientException error) {
            audit.append(actor, sessionId, "ADMIN_USER_CLEANUP_READ", "PLATFORM_USER", userId,
                    requestId, AdminTokenMaterial.sha256(userId), AdminAuditOutcome.FAILED,
                    error.safeCode());
            throw error;
        }
    }

    public CommandResult reconcile(UUID actor, UUID sessionId, UUID commandId, String requestId) {
        authentication.requireActiveSession(actor, sessionId);
        AdminCommand command = repository.findById(commandId).orElseThrow(() ->
                new AdminApiException(HttpStatus.NOT_FOUND, "ADMIN_COMMAND_NOT_FOUND",
                        "Administrator command was not found"));
        if (terminal(command.state())) return view(command, null);
        try {
            PlatformAdminWire.CommandView platform = platformClient.command(
                    commandId, actor.toString(), requestId);
            applyPlatformResult(command, platform);
            AdminCommand updated = repository.findById(commandId).orElseThrow();
            audit.appendCommand(actor, sessionId, "ADMIN_COMMAND_RECONCILE", "ADMIN_COMMAND",
                    commandId.toString(), null, requestId, commandId, command.requestHash(),
                    AdminAuditOutcome.SUCCEEDED, updated.safeErrorCode());
            return view(updated, null);
        } catch (AdminPlatformClientException error) {
            audit.appendCommand(actor, sessionId, "ADMIN_COMMAND_RECONCILE", "ADMIN_COMMAND",
                    commandId.toString(), null, requestId, commandId, command.requestHash(),
                    AdminAuditOutcome.UNKNOWN, error.safeCode());
            return view(command, null);
        }
    }

    private CommandResult dispatch(UUID actor, UUID sessionId, String idempotencyKey,
                                   String operation, String targetType, String targetId, String reason,
                                   Map<String, String> input, String requestId,
                                   PlatformCall call) {
        authentication.requireActiveSession(actor, sessionId);
        requireReason(reason);
        String canonical = operation + "|" + targetType + "|" + (targetId == null ? "" : targetId)
                + "|" + reason.trim() + "|" + new java.util.TreeMap<>(input);
        AdminCommand command = commandService.receive(actor, operation, targetType,
                targetId, required(idempotencyKey, "Idempotency-Key"),
                AdminTokenMaterial.sha256(canonical), requestId);
        if (terminal(command.state()) || command.state() == AdminCommandState.UNKNOWN) {
            return view(command, null);
        }
        if (!repository.transition(command.id(), AdminCommandState.RECEIVED,
                AdminCommandState.DISPATCHING, command.id().toString(), null, null,
                clock.instant(), false)) {
            return view(repository.findById(command.id()).orElseThrow(), null);
        }
        try {
            PlatformAdminWire.CommandView platform = call.execute(command);
            applyPlatformResult(repository.findById(command.id()).orElseThrow(), platform);
            AdminCommand updated = repository.findById(command.id()).orElseThrow();
            audit.appendCommand(actor, sessionId, "ADMIN_COMMAND_DISPATCH", targetType,
                    targetId, reason, requestId, command.id(), command.requestHash(),
                    "FAILED".equals(platform.state()) ? AdminAuditOutcome.FAILED
                            : AdminAuditOutcome.SUCCEEDED,
                    platform.safeErrorCode());
            return view(updated, platform.activationToken(), platform.passwordResetToken());
        } catch (AdminPlatformClientException error) {
            repository.transition(command.id(), AdminCommandState.DISPATCHING,
                    AdminCommandState.UNKNOWN, command.id().toString(), null,
                    error.safeCode(), clock.instant(), false);
            audit.appendCommand(actor, sessionId, "ADMIN_COMMAND_DISPATCH", targetType,
                    targetId, reason, requestId, command.id(), command.requestHash(),
                    AdminAuditOutcome.UNKNOWN,
                    error.safeCode());
            return view(repository.findById(command.id()).orElseThrow(), null);
        }
    }

    private void applyPlatformResult(AdminCommand command, PlatformAdminWire.CommandView platform) {
        AdminCommandState next = switch (platform.state()) {
            case "SUCCEEDED" -> AdminCommandState.SUCCEEDED;
            case "FAILED" -> AdminCommandState.FAILED;
            default -> AdminCommandState.UNKNOWN;
        };
        String safeJson;
        try { safeJson = objectMapper.writeValueAsString(platform.result()); }
        catch (Exception error) { throw new IllegalStateException("Unable to serialize command evidence", error); }
        AdminCommandState expected = command.state() == AdminCommandState.UNKNOWN
                ? AdminCommandState.UNKNOWN : AdminCommandState.DISPATCHING;
        repository.transition(command.id(), expected, next, platform.commandId().toString(), safeJson,
                platform.safeErrorCode(), clock.instant(), terminal(next));
    }

    private CommandResult view(AdminCommand command, String activationToken) {
        return view(command, activationToken, null);
    }

    private CommandResult view(AdminCommand command, String activationToken, String passwordResetToken) {
        Map<String, Object> result = Map.of();
        if (command.resultJson() != null && !command.resultJson().isBlank()) {
            try { result = objectMapper.readValue(command.resultJson(), new TypeReference<>() {}); }
            catch (Exception error) { throw new IllegalStateException("Invalid Admin command evidence", error); }
        }
        return new CommandResult(command.id(), command.operation(), command.targetType(),
                command.targetId(), "PLATFORM_USER".equals(command.targetType())
                        ? command.targetId() : null,
                command.state().name(), result, command.safeErrorCode(), activationToken,
                command.createdAt(), command.updatedAt(), command.completedAt(), passwordResetToken);
    }

    private static boolean terminal(AdminCommandState state) {
        return state == AdminCommandState.SUCCEEDED || state == AdminCommandState.FAILED;
    }
    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "ADMIN_REASON_REQUIRED",
                    "Administrator reason is required");
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank())
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "ADMIN_COMMAND_INVALID",
                    name + " is required");
        return value.trim();
    }
    private static void put(Map<String, String> input, String name, String value) {
        if (value != null && !value.isBlank()) input.put(name, value.trim());
    }

    @FunctionalInterface private interface PlatformCall {
        PlatformAdminWire.CommandView execute(AdminCommand command);
    }

    public record CommandResult(UUID commandId, String operation, String targetType,
                                String targetId, String targetUserId, String state,
                                Map<String, Object> result, String safeErrorCode,
                                String activationToken, Instant createdAt, Instant updatedAt,
                                Instant completedAt,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                String passwordResetToken) {
        public CommandResult { result = result == null ? Map.of() : Map.copyOf(result); }
    }
}
