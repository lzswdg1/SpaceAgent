package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentSystemAdministrationApi;
import com.spaceagent.platform.artifact.api.ArtifactObjectMaintenanceApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationSystemAdministrationApi;
import com.spaceagent.platform.identity.api.IdentitySystemAdministrationApi;
import com.spaceagent.platform.identity.api.IdentityOrganizationAdministrationApi;
import com.spaceagent.platform.identity.api.IdentityUserAdministrationApi;
import com.spaceagent.platform.identity.api.UserCleanupApplicationApi;
import com.spaceagent.platform.inference.api.InferenceSystemAdministrationApi;
import com.spaceagent.platform.integration.api.PlatformSystemAdministrationCommandApi;
import com.spaceagent.platform.integration.domain.PlatformSystemAdministrationCommandRepository;
import com.spaceagent.platform.project.api.ProjectSystemAdministrationApi;
import com.spaceagent.platform.runtime.api.RuntimeSystemAdministrationApi;
import com.spaceagent.platform.tooling.api.ToolingSystemAdministrationApi;
import com.spaceagent.platform.tooling.api.McpRegistryAdministrationApi;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PlatformSystemAdministrationCommandService
        implements PlatformSystemAdministrationCommandApi {
    private static final java.util.Set<String> OPERATIONS = java.util.Set.of(
            "USER_CREATE", "USER_SUSPEND", "USER_RESTORE", "USER_UPDATE", "USER_SESSIONS_REVOKE", "USER_PASSWORD_RESET", "USER_DELETION_PREFLIGHT",
            "USER_DELETION_REQUEST", "ORGANIZATION_CREATE", "ORGANIZATION_UPDATE",
            "ORGANIZATION_DELETE", "ORGANIZATION_MEMBER_ADD",
            "ORGANIZATION_MEMBER_ROLE_UPDATE", "ORGANIZATION_MEMBER_REMOVE",
            "ORGANIZATION_OWNER_TRANSFER", "MCP_REGISTRY_SYNC_REQUEST",
            "MCP_REGISTRY_CANDIDATE_APPROVE", "MCP_REGISTRY_CANDIDATE_REJECT",
            "ARTIFACT_DELETION_RETRY");

    private final PlatformSystemAdministrationCommandRepository repository;
    private final IdentityUserAdministrationApi users;
    private final IdentitySystemAdministrationApi identity;
    private final IdentityOrganizationAdministrationApi organizations;
    private final UserCleanupApplicationApi userCleanup;
    private final AgentSystemAdministrationApi agent;
    private final InferenceSystemAdministrationApi inference;
    private final ProjectSystemAdministrationApi project;
    private final ConversationSystemAdministrationApi conversation;
    private final RuntimeSystemAdministrationApi runtime;
    private final ToolingSystemAdministrationApi tooling;
    private final McpRegistryAdministrationApi mcpRegistry;
    private final ArtifactObjectMaintenanceApplicationApi artifactMaintenance;
    private final ObjectMapper objectMapper;
    private final TimeProvider timeProvider;
    private final boolean userDeletionEnabled;

    public PlatformSystemAdministrationCommandService(
            PlatformSystemAdministrationCommandRepository repository,
            IdentityUserAdministrationApi users,
            IdentitySystemAdministrationApi identity,
            IdentityOrganizationAdministrationApi organizations,
            UserCleanupApplicationApi userCleanup,
            AgentSystemAdministrationApi agent,
            InferenceSystemAdministrationApi inference,
            ProjectSystemAdministrationApi project,
            ConversationSystemAdministrationApi conversation,
            RuntimeSystemAdministrationApi runtime,
            ToolingSystemAdministrationApi tooling,
            McpRegistryAdministrationApi mcpRegistry,
            ArtifactObjectMaintenanceApplicationApi artifactMaintenance,
            ObjectMapper objectMapper,
            TimeProvider timeProvider,
            @Value("${platform.identity.user-cleanup.deletion-enabled:false}")
            boolean userDeletionEnabled) {
        this.repository = repository;
        this.users = users;
        this.identity = identity;
        this.organizations = organizations;
        this.userCleanup = userCleanup;
        this.agent = agent;
        this.inference = inference;
        this.project = project;
        this.conversation = conversation;
        this.runtime = runtime;
        this.tooling = tooling;
        this.mcpRegistry = mcpRegistry;
        this.artifactMaintenance = artifactMaintenance;
        this.objectMapper = objectMapper;
        this.timeProvider = timeProvider;
        this.userDeletionEnabled = userDeletionEnabled;
    }

    @Override
    @Transactional
    public CommandView execute(CommandRequest request) {
        validate(request);
        Instant now = timeProvider.now();
        var received = new PlatformSystemAdministrationCommandRepository.CommandRecord(
                request.commandId(), request.idempotencyHash(), request.operation(),
                request.requestHash(), UUID.fromString(request.actorId()), request.targetUserId(),
                "RECEIVED", null, null, now, now, null);
        if (!repository.insert(received)) {
            var existing = repository.findById(request.commandId())
                    .or(() -> repository.findByOperationAndIdempotencyHash(
                            request.operation(), request.idempotencyHash()))
                    .orElseThrow(() -> new IllegalStateException("Platform admin command conflict is missing"));
            if (!existing.operation().equals(request.operation())
                    || !existing.requestHash().equals(request.requestHash())) {
                throw new BusinessException("Idempotency key conflicts with another request",
                        HttpStatus.CONFLICT, "SYSTEM_ADMIN_IDEMPOTENCY_CONFLICT");
            }
            return view(existing, null);
        }

        try {
            Execution execution = switch (request.operation()) {
                case "USER_CREATE" -> create(request);
                case "USER_SUSPEND" -> suspend(request);
                case "USER_RESTORE" -> restore(request);
                case "USER_UPDATE" -> updateUser(request);
                case "USER_SESSIONS_REVOKE" -> revokeSessions(request);
                case "USER_PASSWORD_RESET" -> passwordReset(request);
                case "USER_DELETION_PREFLIGHT" -> preflight(request);
                case "USER_DELETION_REQUEST" -> requestDeletion(request);
                case "ORGANIZATION_CREATE" -> createOrganization(request);
                case "ORGANIZATION_UPDATE" -> updateOrganization(request);
                case "ORGANIZATION_DELETE" -> deleteOrganization(request);
                case "ORGANIZATION_MEMBER_ADD" -> addOrganizationMember(request);
                case "ORGANIZATION_MEMBER_ROLE_UPDATE" -> updateOrganizationMemberRole(request);
                case "ORGANIZATION_MEMBER_REMOVE" -> removeOrganizationMember(request);
                case "ORGANIZATION_OWNER_TRANSFER" -> transferOrganizationOwnership(request);
                case "MCP_REGISTRY_SYNC_REQUEST" -> requestMcpRegistrySync(request);
                case "MCP_REGISTRY_CANDIDATE_APPROVE" -> approveMcpRegistryCandidate(request);
                case "MCP_REGISTRY_CANDIDATE_REJECT" -> rejectMcpRegistryCandidate(request);
                case "ARTIFACT_DELETION_RETRY" -> retryArtifactDeletion(request);
                default -> throw new BusinessException("Unsupported administrator operation",
                        HttpStatus.BAD_REQUEST, "SYSTEM_ADMIN_OPERATION_INVALID");
            };
            String json = objectMapper.writeValueAsString(execution.safeResult());
            repository.complete(request.commandId(), "SUCCEEDED", json, null, timeProvider.now());
            return view(repository.findById(request.commandId()).orElseThrow(), execution.activationToken(), execution.passwordResetToken());
        } catch (BusinessException error) {
            repository.complete(request.commandId(), "FAILED", "{}", error.getCode(), timeProvider.now());
            return view(repository.findById(request.commandId()).orElseThrow(), null);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to execute platform administrator command", error);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CommandView> command(UUID commandId) {
        return repository.findById(commandId).map(value -> view(value, null));
    }

    private Execution create(CommandRequest request) {
        requireReason(request.reason());
        var created = users.createPendingUser(new IdentityUserAdministrationApi.CreatePendingUserCommand(
                requiredInput(request, "loginName"), request.input().get("displayName"),
                request.input().get("organizationName"), request.input().get("organizationSlug"),
                request.actorId()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", created.userId());
        result.put("organizationId", created.organizationId());
        result.put("status", created.status());
        result.put("activationRequired", true);
        result.put("activationExpiresAt", created.activationExpiresAt().toString());
        result.put("activationTokenReplayable", false);
        return new Execution(result, created.activationToken());
    }

    private Execution suspend(CommandRequest request) {
        var result = users.suspendUser(new IdentityUserAdministrationApi.UserLifecycleCommand(
                requiredTarget(request), request.actorId(), request.reason()));
        return new Execution(Map.of("userId", result.userId(), "status", result.status(),
                "updatedAt", result.updatedAt().toString(), "sessionsRevoked", result.sessionsRevoked()), null);
    }

    private Execution restore(CommandRequest request) {
        var result = users.restoreUser(new IdentityUserAdministrationApi.UserLifecycleCommand(
                requiredTarget(request), request.actorId(), request.reason()));
        return new Execution(Map.of("userId", result.userId(), "status", result.status(),
                "updatedAt", result.updatedAt().toString(), "sessionsRevoked", result.sessionsRevoked()), null);
    }

    private Execution passwordReset(CommandRequest request) {
        var reset = users.issuePasswordReset(new IdentityUserAdministrationApi.UserLifecycleCommand(
                requiredTarget(request), request.actorId(), request.reason()));
        return new Execution(Map.of("userId", reset.userId(), "expiresAt", reset.expiresAt().toString(),
                "sessionsRevoked", true, "tokenReplayable", false, "delivery", "ADMIN_SECURE_HANDOFF_REQUIRED"),
                null, reset.token());
    }

    private Execution updateUser(CommandRequest request) {
        var result = users.updateUser(new IdentityUserAdministrationApi.UserProfileCommand(
                requiredTarget(request), requiredInput(request, "displayName"), request.actorId(), request.reason()));
        return new Execution(Map.of("userId", result.userId(), "status", result.status(),
                "updatedAt", result.updatedAt().toString(), "sessionsRevoked", false), null);
    }

    private Execution revokeSessions(CommandRequest request) {
        var result = users.revokeSessions(new IdentityUserAdministrationApi.UserLifecycleCommand(
                requiredTarget(request), request.actorId(), request.reason()));
        return new Execution(Map.of("userId", result.userId(), "status", result.status(),
                "updatedAt", result.updatedAt().toString(), "sessionsRevoked", true), null);
    }

    private Execution preflight(CommandRequest request) {
        requireReason(request.reason());
        String userId = requiredTarget(request);
        var identityEvidence = identity.deletionEvidence(userId);
        var projectEvidence = project.deletionEvidence(userId);
        var agentEvidence = agent.deletionEvidence(userId);
        var inferenceEvidence = inference.deletionEvidence(userId);
        var runtimeEvidence = runtime.deletionEvidence(userId);
        var toolingEvidence = tooling.deletionEvidence(userId);
        var conversationEvidence = conversation.deletionEvidence(userId);
        List<Map<String, Object>> blockers = new ArrayList<>();
        identityEvidence.ownedOrganizations().stream()
                .filter(value -> value.activeMembers() > 1)
                .forEach(value -> blockers.add(blocker("ORGANIZATION_OWNERSHIP_TRANSFER_REQUIRED",
                        "ORGANIZATION", value.organizationId(), value.activeMembers())));
        addCountBlocker(blockers, "PROJECT_TRANSFER_OR_PURGE_REQUIRED", "PROJECT",
                projectEvidence.ownedProjects());
        addCountBlocker(blockers, "SHARED_PROJECT_OWNERSHIP_BLOCKER", "PROJECT",
                projectEvidence.sharedOwnedProjects());
        addCountBlocker(blockers, "ACTIVE_WORKSPACE_BLOCKER", "WORKSPACE",
                projectEvidence.activeWorkspaces());
        addCountBlocker(blockers, "ACTIVE_RUNTIME_BLOCKER", "RUNTIME", runtimeEvidence.activeRuns());
        addCountBlocker(blockers, "RECOVERING_RUNTIME_BLOCKER", "RUNTIME", runtimeEvidence.recoveringRuns());
        addCountBlocker(blockers, "AGENT_TRANSFER_OR_PURGE_REQUIRED", "AGENT", agentEvidence.ownedAgents());
        addCountBlocker(blockers, "PROVIDER_TRANSFER_OR_PURGE_REQUIRED", "PROVIDER",
                inferenceEvidence.ownedProviders());
        addCountBlocker(blockers, "MODEL_POOL_TRANSFER_OR_PURGE_REQUIRED", "MODEL_POOL",
                inferenceEvidence.ownedModelPools());
        addCountBlocker(blockers, "UNKNOWN_MODEL_EFFECT_BLOCKER", "MODEL_CALL",
                inferenceEvidence.unknownModelCalls());
        addCountBlocker(blockers, "MCP_CONNECTION_PURGE_REQUIRED", "MCP",
                toolingEvidence.managedConnections());
        addCountBlocker(blockers, "ACTIVE_CHECKOUT_GRANT_BLOCKER", "MCP_GRANT",
                toolingEvidence.activeCheckoutGrants());
        addCountBlocker(blockers, "UNKNOWN_TOOL_EFFECT_BLOCKER", "TOOL_CALL",
                toolingEvidence.unknownToolExecutions());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("identity", identityEvidence);
        evidence.put("project", projectEvidence);
        evidence.put("agent", agentEvidence);
        evidence.put("inference", inferenceEvidence);
        evidence.put("runtime", runtimeEvidence);
        evidence.put("tooling", toolingEvidence);
        evidence.put("conversation", conversationEvidence);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("eligible", blockers.isEmpty());
        result.put("blockers", blockers);
        result.put("evidence", evidence);
        result.put("physicalDeletionEnabled", userDeletionEnabled);
        return new Execution(result, null);
    }

    private Execution requestDeletion(CommandRequest request) {
        requireReason(request.reason());
        if (!userDeletionEnabled) {
            throw new BusinessException("User deletion is disabled", HttpStatus.CONFLICT,
                    "USER_DELETION_DISABLED");
        }
        Execution checked = preflight(request);
        if (!Boolean.TRUE.equals(checked.safeResult().get("eligible"))) {
            throw new BusinessException("User deletion preflight has blockers", HttpStatus.CONFLICT,
                    "USER_DELETION_BLOCKED");
        }
        var job = userCleanup.enqueue(new UserCleanupApplicationApi.EnqueueCommand(
                requiredTarget(request), request.commandId(), UUID.fromString(request.actorId()),
                request.requestHash()));
        return new Execution(Map.of("userId", job.userId(), "cleanupState", job.state().name(),
                "retentionNotBefore", job.retentionNotBefore().toString(),
                "physicalDeletionEnabled", true), null);
    }

    private Execution createOrganization(CommandRequest request) {
        var result = organizations.create(
                new IdentityOrganizationAdministrationApi.CreateOrganizationCommand(
                        requiredInput(request, "ownerUserId"), requiredInput(request, "name"),
                        requiredInput(request, "slug"), request.actorId(), request.reason()));
        return organizationExecution(result);
    }

    private Execution updateOrganization(CommandRequest request) {
        var result = organizations.update(
                new IdentityOrganizationAdministrationApi.UpdateOrganizationCommand(
                        requiredTarget(request, "Organization"), requiredInput(request, "name"),
                        requiredInput(request, "slug"), request.actorId(), request.reason()));
        return organizationExecution(result);
    }

    private Execution deleteOrganization(CommandRequest request) {
        var result = organizations.requestDeletion(
                new IdentityOrganizationAdministrationApi.DeleteOrganizationCommand(
                        requiredTarget(request, "Organization"), request.actorId(), request.reason()));
        return organizationExecution(result);
    }

    private Execution addOrganizationMember(CommandRequest request) {
        var result = organizations.addMember(
                new IdentityOrganizationAdministrationApi.AddMemberCommand(
                        requiredTarget(request, "Organization"), requiredInput(request, "userId"),
                        requiredInput(request, "role"), request.actorId(), request.reason()));
        return membershipExecution(result);
    }

    private Execution updateOrganizationMemberRole(CommandRequest request) {
        var result = organizations.updateMemberRole(
                new IdentityOrganizationAdministrationApi.UpdateMemberRoleCommand(
                        requiredTarget(request, "Organization"), requiredInput(request, "userId"),
                        requiredInput(request, "role"), request.actorId(), request.reason()));
        return membershipExecution(result);
    }

    private Execution removeOrganizationMember(CommandRequest request) {
        var result = organizations.removeMember(
                new IdentityOrganizationAdministrationApi.RemoveMemberCommand(
                        requiredTarget(request, "Organization"), requiredInput(request, "userId"),
                        request.actorId(), request.reason()));
        return membershipExecution(result);
    }

    private Execution transferOrganizationOwnership(CommandRequest request) {
        var result = organizations.transferOwnership(
                new IdentityOrganizationAdministrationApi.TransferOwnershipCommand(
                        requiredTarget(request, "Organization"),
                        requiredInput(request, "newOwnerUserId"), request.actorId(),
                        request.reason()));
        return organizationExecution(result);
    }

    private Execution requestMcpRegistrySync(CommandRequest request) {
        requireReason(request.reason());
        var result = mcpRegistry.enqueue(request.actorId());
        return new Execution(Map.of(
                "syncJobId", result.id(),
                "sourceKey", result.sourceKey(),
                "state", result.state().name(),
                "watermarkAt", result.watermarkAt().toString()), null);
    }

    private Execution approveMcpRegistryCandidate(CommandRequest request) {
        var result = mcpRegistry.approve(new McpRegistryAdministrationApi.ReviewCommand(
                requiredTarget(request, "Registry candidate"), request.actorId(), request.reason()));
        return registryReviewExecution(result);
    }

    private Execution rejectMcpRegistryCandidate(CommandRequest request) {
        var result = mcpRegistry.reject(new McpRegistryAdministrationApi.ReviewCommand(
                requiredTarget(request, "Registry candidate"), request.actorId(), request.reason()));
        return registryReviewExecution(result);
    }

    private Execution retryArtifactDeletion(CommandRequest request) {
        requireReason(request.reason());
        String objectId = requiredTarget(request, "Artifact object");
        try {
            UUID.fromString(objectId);
        } catch (RuntimeException error) {
            throw new BusinessException("Artifact object identifier is invalid",
                    HttpStatus.BAD_REQUEST, "SYSTEM_ADMIN_COMMAND_INVALID");
        }
        var result = artifactMaintenance.retryBlockedDeletion(
                new ArtifactObjectMaintenanceApplicationApi.RetryBlockedDeletionCommand(
                        requiredInput(request, "tenantId"), objectId));
        return new Execution(Map.of(
                "objectId", result.objectId(),
                "deletionJobId", result.id(),
                "state", result.state(),
                "revision", result.revision()), null);
    }

    private static Execution registryReviewExecution(
            McpRegistryAdministrationApi.ReviewResult value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateId", value.candidateId());
        result.put("reviewState", value.reviewState().name());
        result.put("registryName", value.registryName());
        result.put("registryVersion", value.registryVersion());
        if (value.publishedEntryId() != null) result.put("publishedEntryId", value.publishedEntryId());
        if (value.publishedVersionId() != null) result.put("publishedVersionId", value.publishedVersionId());
        if (value.authType() != null) result.put("authType", value.authType().name());
        result.put("revision", value.revision());
        result.put("reviewedAt", value.reviewedAt().toString());
        return new Execution(Map.copyOf(result), null);
    }

    private static Execution membershipExecution(
            IdentityOrganizationAdministrationApi.MembershipResult value) {
        return new Execution(Map.of(
                "organizationId", value.organizationId(),
                "userId", value.userId(),
                "role", value.role(),
                "status", value.status(),
                "joinedAt", value.joinedAt().toString(),
                "updatedAt", value.updatedAt().toString()), null);
    }

    private static Execution organizationExecution(
            IdentityOrganizationAdministrationApi.OrganizationResult value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organizationId", value.organizationId());
        result.put("name", value.name());
        result.put("slug", value.slug());
        result.put("creatorUserId", value.creatorUserId());
        result.put("status", value.status());
        result.put("activeMembers", value.activeMembers());
        result.put("createdAt", value.createdAt().toString());
        result.put("updatedAt", value.updatedAt().toString());
        if (value.deletionRequestedAt() != null) {
            result.put("deletionRequestedAt", value.deletionRequestedAt().toString());
        }
        return new Execution(Map.copyOf(result), null);
    }

    private CommandView view(PlatformSystemAdministrationCommandRepository.CommandRecord record,
                             String activationToken) {
        return view(record, activationToken, null);
    }

    private CommandView view(PlatformSystemAdministrationCommandRepository.CommandRecord record,
                             String activationToken, String passwordResetToken) {
        Map<String, Object> result = Map.of();
        if (record.resultJson() != null && !record.resultJson().isBlank()) {
            try { result = objectMapper.readValue(record.resultJson(), new TypeReference<>() {}); }
            catch (Exception error) { throw new IllegalStateException("Invalid command result evidence", error); }
        }
        return new CommandView(record.commandId(), record.operation(), record.targetUserId(),
                record.state(), result, record.safeErrorCode(), activationToken, record.createdAt(),
                record.updatedAt(), record.completedAt(), passwordResetToken);
    }

    private static void validate(CommandRequest request) {
        if (request.commandId() == null || request.actorId() == null
                || request.idempotencyHash() == null || request.idempotencyHash().length() != 64
                || request.requestHash() == null || request.requestHash().length() != 64
                || !OPERATIONS.contains(request.operation())) {
            throw new BusinessException("Administrator command is invalid", HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN_COMMAND_INVALID");
        }
    }

    private static String requiredInput(CommandRequest request, String name) {
        String value = request.input().get(name);
        if (value == null || value.isBlank()) throw new BusinessException(name + " is required",
                HttpStatus.BAD_REQUEST, "SYSTEM_ADMIN_COMMAND_INVALID");
        return value;
    }
    private static String requiredTarget(CommandRequest request) {
        return requiredTarget(request, "User");
    }
    private static String requiredTarget(CommandRequest request, String type) {
        if (request.targetUserId() == null || request.targetUserId().isBlank())
            throw new BusinessException("Target " + type + " is required", HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN_COMMAND_INVALID");
        return request.targetUserId();
    }
    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new BusinessException("Administrator reason is required", HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN_REASON_REQUIRED");
    }
    private static void addCountBlocker(List<Map<String, Object>> blockers, String code,
                                        String type, long count) {
        if (count > 0) blockers.add(blocker(code, type, null, count));
    }
    private static Map<String, Object> blocker(String code, String type, String resourceId, long count) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code);
        value.put("resourceType", type);
        if (resourceId != null) value.put("resourceId", resourceId);
        value.put("count", count);
        return Map.copyOf(value);
    }
    private record Execution(Map<String, Object> safeResult, String activationToken, String passwordResetToken) {
        Execution(Map<String,Object> result, String activationToken) { this(result, activationToken, null); }
    }
}
