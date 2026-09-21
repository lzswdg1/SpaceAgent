package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.agent.api.AgentSystemAdministrationApi;
import com.spaceagent.platform.conversation.api.ConversationSystemAdministrationApi;
import com.spaceagent.platform.identity.api.IdentitySystemAdministrationApi;
import com.spaceagent.platform.identity.api.CleanupAdministrationApi;
import com.spaceagent.platform.identity.api.UserCleanupApplicationApi;
import com.spaceagent.platform.inference.api.InferenceSystemAdministrationApi;
import com.spaceagent.platform.integration.infrastructure.PlatformReleaseProperties;
import com.spaceagent.platform.integration.infrastructure.SystemAdminAuthenticationDetails;
import com.spaceagent.platform.integration.api.PlatformSystemAdministrationCommandApi;
import com.spaceagent.platform.integration.application.PlatformUserResourceAdministrationService;
import com.spaceagent.platform.project.api.ProjectSystemAdministrationApi;
import com.spaceagent.platform.runtime.api.RuntimeSystemAdministrationApi;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.platform.tooling.api.ToolingSystemAdministrationApi;
import com.spaceagent.platform.tooling.api.McpRegistryAdministrationApi;
import com.spaceagent.platform.tooling.domain.McpRegistryReviewState;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncState;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.time.TimeProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/system-admin/v1")
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PlatformSystemAdministrationHttpController {
    private final IdentitySystemAdministrationApi identity;
    private final InferenceSystemAdministrationApi inference;
    private final AgentSystemAdministrationApi agent;
    private final ProjectSystemAdministrationApi project;
    private final ConversationSystemAdministrationApi conversation;
    private final RuntimeSystemAdministrationApi runtime;
    private final ToolingSystemAdministrationApi tooling;
    private final McpRegistryAdministrationApi mcpRegistry;
    private final PlatformReleaseProperties release;
    private final TimeProvider timeProvider;
    private final PlatformSystemAdministrationCommandApi commands;
    private final UserCleanupApplicationApi userCleanup;
    private final CleanupAdministrationApi cleanupAdministration;
    private final PlatformUserResourceAdministrationService userResources;

    public PlatformSystemAdministrationHttpController(
            IdentitySystemAdministrationApi identity,
            InferenceSystemAdministrationApi inference,
            AgentSystemAdministrationApi agent,
            ProjectSystemAdministrationApi project,
            ConversationSystemAdministrationApi conversation,
            RuntimeSystemAdministrationApi runtime,
            ToolingSystemAdministrationApi tooling,
            McpRegistryAdministrationApi mcpRegistry,
            PlatformSystemAdministrationCommandApi commands,
            UserCleanupApplicationApi userCleanup,
            CleanupAdministrationApi cleanupAdministration,
            PlatformUserResourceAdministrationService userResources,
            PlatformReleaseProperties release,
            TimeProvider timeProvider) {
        this.identity = identity;
        this.inference = inference;
        this.agent = agent;
        this.project = project;
        this.conversation = conversation;
        this.runtime = runtime;
        this.tooling = tooling;
        this.mcpRegistry = mcpRegistry;
        this.commands = commands;
        this.userCleanup = userCleanup;
        this.cleanupAdministration = cleanupAdministration;
        this.userResources = userResources;
        this.release = release;
        this.timeProvider = timeProvider;
    }

    @GetMapping("/health")
    public ApiResponse<HealthProjection> health() {
        return ApiResponse.ok(new HealthProjection("UP", release.getVersion(),
                release.getExpectedSchemaVersion(), timeProvider.now()));
    }

    @GetMapping("/overview")
    public ApiResponse<OverviewProjection> overview(
            @RequestParam(defaultValue = "24h") String window) {
        Instant now = timeProvider.now();
        Instant since = now.minus(windowHours(window), ChronoUnit.HOURS);
        return ApiResponse.ok(new OverviewProjection(window(window), now, release.getVersion(),
                release.getExpectedSchemaVersion(), "NOT_CHECKED",
                identity.overview(since, since, now.minus(5, ChronoUnit.MINUTES)),
                inference.overview(), agent.overview(), project.overview(), conversation.overview(),
                runtime.overview(), tooling.overview()));
    }

    @GetMapping("/users")
    public ApiResponse<SystemAdministrationPage<IdentitySystemAdministrationApi.UserSummary>> users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sort) {
        return ApiResponse.ok(identity.users(page, pageSize, query, status, sort));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<IdentitySystemAdministrationApi.UserDetail> user(@PathVariable String userId) {
        return ApiResponse.ok(identity.user(userId).orElseThrow(() ->
                new com.spaceagent.shared.exception.BusinessException(
                        "User not found", org.springframework.http.HttpStatus.NOT_FOUND,
                        "SYSTEM_ADMIN_USER_NOT_FOUND")));
    }

    @GetMapping("/users/{userId}/providers")
    public ApiResponse<SystemAdministrationPage<CredentialItem>> userProviders(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        requireUser(userId);
        var source = inference.providerCredentialsByOwner(userId, page, pageSize);
        return ApiResponse.ok(providerCredentials(source));
    }

    @GetMapping("/users/{userId}/agents")
    public ApiResponse<SystemAdministrationPage<AgentSystemAdministrationApi.AgentSummary>> userAgents(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        requireUser(userId);
        return ApiResponse.ok(agent.agentsByOwner(userId, page, pageSize));
    }

    @GetMapping("/users/{userId}/resource-overview")
    public ApiResponse<PlatformUserResourceAdministrationService.UserResourceOverview>
            userResourceOverview(@PathVariable String userId) {
        requireUser(userId);
        return ApiResponse.ok(userResources.overview(userId));
    }

    @GetMapping("/users/{userId}/resources")
    public ApiResponse<SystemAdministrationPage<UserResourceSummary>> userResources(
            @PathVariable String userId,
            @RequestParam String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        requireUser(userId);
        return ApiResponse.ok(userResources.resources(userId, kind, page, pageSize));
    }

    @GetMapping("/organizations")
    public ApiResponse<SystemAdministrationPage<IdentitySystemAdministrationApi.OrganizationSummary>> organizations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(identity.organizations(page, pageSize, query, status));
    }

    @GetMapping("/organizations/{organizationId}/members")
    public ApiResponse<SystemAdministrationPage<IdentitySystemAdministrationApi.OrganizationMemberSummary>>
            organizationMembers(
            @PathVariable String organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role) {
        return ApiResponse.ok(identity.organizationMembers(
                organizationId, page, pageSize, query, status, role));
    }

    @PostMapping("/organizations")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> createOrganization(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody CreateOrganizationRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        Map<String, String> input = new LinkedHashMap<>();
        input.put("ownerUserId", request.ownerUserId());
        input.put("name", request.name());
        input.put("slug", request.slug());
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "ORGANIZATION_CREATE", null, request.reason(), input, details)));
    }

    @PatchMapping("/organizations/{organizationId}")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> updateOrganization(
            @PathVariable String organizationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody UpdateOrganizationRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "ORGANIZATION_UPDATE", organizationId, request.reason(),
                Map.of("name", request.name(), "slug", request.slug()), details)));
    }

    @PostMapping("/organizations/{organizationId}/deletion-jobs")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> deleteOrganization(
            @PathVariable String organizationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "ORGANIZATION_DELETE", organizationId, request.reason(), Map.of(), details)));
    }

    @PostMapping("/organizations/{organizationId}/members")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> addOrganizationMember(
            @PathVariable String organizationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody OrganizationMemberRoleRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "ORGANIZATION_MEMBER_ADD", organizationId, request.reason(),
                Map.of("userId", request.userId(), "role", request.role()), details)));
    }

    @PatchMapping("/organizations/{organizationId}/members/{userId}")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> updateOrganizationMemberRole(
            @PathVariable String organizationId,
            @PathVariable String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody OrganizationRoleRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "ORGANIZATION_MEMBER_ROLE_UPDATE", organizationId, request.reason(),
                Map.of("userId", userId, "role", request.role()), details)));
    }

    @DeleteMapping("/organizations/{organizationId}/members/{userId}")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> removeOrganizationMember(
            @PathVariable String organizationId,
            @PathVariable String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "ORGANIZATION_MEMBER_REMOVE", organizationId, request.reason(),
                Map.of("userId", userId), details)));
    }

    @PostMapping("/organizations/{organizationId}/ownership-transfers")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> transferOrganizationOwnership(
            @PathVariable String organizationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody TransferOrganizationOwnershipRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "ORGANIZATION_OWNER_TRANSFER", organizationId, request.reason(),
                Map.of("newOwnerUserId", request.newOwnerUserId()), details)));
    }

    @GetMapping("/credential-inventory")
    public ApiResponse<SystemAdministrationPage<CredentialItem>> credentials(
            @RequestParam String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        String normalized = kind.trim().toLowerCase(Locale.ROOT);
        return ApiResponse.ok(switch (normalized) {
            case "provider" -> providerCredentials(page, pageSize);
            case "agent-key" -> agentCredentials(page, pageSize);
            case "mcp" -> mcpCredentials(page, pageSize);
            default -> throw new com.spaceagent.shared.exception.BusinessException(
                    "Credential inventory kind is invalid", org.springframework.http.HttpStatus.BAD_REQUEST,
                    "SYSTEM_ADMIN_CREDENTIAL_KIND_INVALID");
        });
    }

    @GetMapping("/mcp-registry/sync-jobs")
    public ApiResponse<SystemAdministrationPage<McpRegistryAdministrationApi.SyncJobView>>
            mcpRegistrySyncJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) McpRegistrySyncState state) {
        return ApiResponse.ok(mcpRegistry.syncJobs(page, pageSize, state));
    }

    @GetMapping("/mcp-registry/candidates")
    public ApiResponse<SystemAdministrationPage<McpRegistryAdministrationApi.CandidateView>>
            mcpRegistryCandidates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) McpRegistryReviewState state,
            @RequestParam(required = false) String query) {
        return ApiResponse.ok(mcpRegistry.candidates(page, pageSize, state, query));
    }

    @GetMapping("/mcp-registry/candidates/{candidateId}")
    public ApiResponse<McpRegistryAdministrationApi.CandidateDetail> mcpRegistryCandidate(
            @PathVariable String candidateId) {
        return ApiResponse.ok(mcpRegistry.candidate(candidateId));
    }

    @PostMapping("/mcp-registry/sync-jobs")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> requestMcpRegistrySync(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "MCP_REGISTRY_SYNC_REQUEST", null, request.reason(), Map.of(), details)));
    }

    @PostMapping("/mcp-registry/candidates/{candidateId}/approvals")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> approveMcpRegistryCandidate(
            @PathVariable String candidateId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "MCP_REGISTRY_CANDIDATE_APPROVE", candidateId, request.reason(), Map.of(), details)));
    }

    @PostMapping("/mcp-registry/candidates/{candidateId}/rejections")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> rejectMcpRegistryCandidate(
            @PathVariable String candidateId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "MCP_REGISTRY_CANDIDATE_REJECT", candidateId, request.reason(), Map.of(), details)));
    }

    @PostMapping("/artifact-objects/{objectId}/deletion-retries")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> retryArtifactDeletion(
            @PathVariable String objectId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ArtifactDeletionRetryRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(
                commandId, idempotencyKey, "ARTIFACT_DELETION_RETRY", objectId,
                request.reason(), Map.of("tenantId", request.tenantId()), details)));
    }

    public record ArtifactDeletionRetryRequest(
            @NotBlank @Size(max = 64) String tenantId,
            @NotBlank @Size(max = 500) String reason) { }

    @PostMapping("/users")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> createUser(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody CreateUserRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        Map<String, String> input = new LinkedHashMap<>();
        input.put("loginName", request.loginName());
        put(input, "displayName", request.displayName());
        put(input, "organizationName", request.organizationName());
        put(input, "organizationSlug", request.organizationSlug());
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey, "USER_CREATE",
                null, request.reason(), input, details)));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/users/{userId}")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> updateUser(
            @PathVariable String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody UserProfileRequest request, Authentication authentication) {
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey, "USER_UPDATE", userId,
                request.reason(), Map.of("displayName", request.displayName()), details(authentication, commandId))));
    }

    @PostMapping("/users/{userId}/session-revocations")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> revokeSessions(
            @PathVariable String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request, Authentication authentication) {
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey, "USER_SESSIONS_REVOKE",
                userId, request.reason(), Map.of(), details(authentication, commandId))));
    }

    @PostMapping("/users/{userId}/password-resets")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> passwordReset(
            @PathVariable String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request, Authentication authentication) {
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey, "USER_PASSWORD_RESET",
                userId, request.reason(), Map.of(), details(authentication, commandId))));
    }

    public record UserProfileRequest(@NotBlank @Size(max = 120) String displayName,
                                     @NotBlank @Size(max = 500) String reason) { }

    @PostMapping("/users/{userId}/suspend")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> suspendUser(
            @PathVariable String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey, "USER_SUSPEND",
                userId, request.reason(), Map.of(), details)));
    }

    @PostMapping("/users/{userId}/restore")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> restoreUser(
            @PathVariable String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey, "USER_RESTORE",
                userId, request.reason(), Map.of(), details)));
    }

    @PostMapping("/users/{userId}/deletion-requests")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> deletionPreflight(
            @PathVariable String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "USER_DELETION_PREFLIGHT", userId, request.reason(), Map.of(), details)));
    }

    @PostMapping("/users/{userId}/deletion-jobs")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> requestDeletion(
            @PathVariable String userId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader("X-Admin-Command-ID") UUID commandId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication) {
        var details = details(authentication, commandId);
        return ApiResponse.ok(commands.execute(command(commandId, idempotencyKey,
                "USER_DELETION_REQUEST", userId, request.reason(), Map.of(), details)));
    }

    @GetMapping("/users/{userId}/deletion-jobs/current")
    public ApiResponse<UserCleanupJobProjection> userCleanupJob(@PathVariable String userId) {
        var job = userCleanup.findJob(userId).orElseThrow(() ->
                new com.spaceagent.shared.exception.BusinessException("User cleanup job not found",
                        org.springframework.http.HttpStatus.NOT_FOUND, "USER_CLEANUP_NOT_FOUND"));
        return ApiResponse.ok(new UserCleanupJobProjection(job, userCleanup.findSteps(userId)));
    }

    @GetMapping("/commands/{commandId}")
    public ApiResponse<PlatformSystemAdministrationCommandApi.CommandView> command(
            @PathVariable UUID commandId) {
        return ApiResponse.ok(commands.command(commandId).orElseThrow(() ->
                new com.spaceagent.shared.exception.BusinessException("Command not found",
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "SYSTEM_ADMIN_COMMAND_NOT_FOUND")));
    }

    @GetMapping("/cleanup-jobs")
    public ApiResponse<SystemAdministrationPage<CleanupAdministrationApi.CleanupJobSummary>>
            cleanupJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String query) {
        return ApiResponse.ok(cleanupAdministration.jobs(page, pageSize, kind, state, query));
    }

    @GetMapping("/cleanup-jobs/overview")
    public ApiResponse<CleanupAdministrationApi.CleanupOverview> cleanupOverview() {
        return ApiResponse.ok(cleanupAdministration.overview());
    }

    @GetMapping("/cleanup-jobs/{kind}/{subjectId}")
    public ApiResponse<CleanupAdministrationApi.CleanupJobDetail> cleanupJob(
            @PathVariable String kind, @PathVariable String subjectId) {
        return ApiResponse.ok(cleanupAdministration.job(kind, subjectId));
    }

    private SystemAdministrationPage<CredentialItem> providerCredentials(int page, int pageSize) {
        var source = inference.providerCredentials(page, pageSize);
        return providerCredentials(source);
    }

    private SystemAdministrationPage<CredentialItem> providerCredentials(
            SystemAdministrationPage<InferenceSystemAdministrationApi.ProviderCredential> source) {
        return page(source, source.items().stream().map(value -> new CredentialItem(
                value.kind(), value.id(), value.organizationId(), value.ownerUserId(), value.id(),
                value.name(), value.baseUrl(), value.endpointHost(), value.authType(),
                value.secretConfigured(), value.secretHint(), value.secretFingerprint(),
                value.secretKeyVersion(), value.connectionStatus(), value.lastTestedAt(),
                Map.of("providerType", value.providerType(), "enabled", value.enabled(),
                        "defaultProvider", value.defaultProvider(), "modelCount", value.modelCount(),
                        "modelPoolUsageCount", value.modelPoolUsageCount(),
                        "lastTestLatencyMs", value.lastTestLatencyMs() == null ? -1 : value.lastTestLatencyMs(),
                        "safeErrorCode", value.safeErrorCode() == null ? "" : value.safeErrorCode()))).toList());
    }

    private void requireUser(String userId) {
        identity.user(userId).orElseThrow(() ->
                new com.spaceagent.shared.exception.BusinessException(
                        "User not found", org.springframework.http.HttpStatus.NOT_FOUND,
                        "SYSTEM_ADMIN_USER_NOT_FOUND"));
    }

    private SystemAdministrationPage<CredentialItem> agentCredentials(int page, int pageSize) {
        var source = agent.agentKeyCredentials(page, pageSize);
        return page(source, source.items().stream().map(value -> new CredentialItem(
                value.kind(), value.id(), value.organizationId(), value.ownerUserId(), value.agentId(),
                value.name(), null, null, "HASHED_API_KEY", true, value.keyPrefix(), null, null,
                value.revokedAt() == null && value.enabled() ? "ACTIVE" : "REVOKED", value.createdAt(),
                Map.of("agentName", value.agentName(), "scopes", value.scopes(),
                        "lastUsedAt", string(value.lastUsedAt()), "expiresAt", string(value.expiresAt()),
                        "revokedAt", string(value.revokedAt())))).toList());
    }

    private SystemAdministrationPage<CredentialItem> mcpCredentials(int page, int pageSize) {
        var source = tooling.mcpCredentials(page, pageSize);
        return page(source, source.items().stream().map(value -> new CredentialItem(
                value.kind(), value.id(), value.organizationId(), value.managedByUserId(), value.profile(),
                value.profile(), value.endpointUrl(), value.endpointHost(), value.authType(),
                value.authConfigured(), null, null, null, value.state(), value.updatedAt(),
                Map.of("transport", value.transport(), "externalAccountName",
                        value.externalAccountName() == null ? "" : value.externalAccountName(),
                        "createdAt", string(value.createdAt()), "revokedAt", string(value.revokedAt())))).toList());
    }

    private static <S> SystemAdministrationPage<CredentialItem> page(
            SystemAdministrationPage<S> source, List<CredentialItem> items) {
        return new SystemAdministrationPage<>(items, source.page(), source.pageSize(), source.total(),
                source.generatedAt());
    }

    private static String string(Instant value) { return value == null ? "" : value.toString(); }

    private PlatformSystemAdministrationCommandApi.CommandRequest command(
            UUID commandId, String idempotencyKey, String operation, String targetUserId,
            String reason, Map<String, String> input, SystemAdminAuthenticationDetails details) {
        String canonical = operation + "|" + (targetUserId == null ? "" : targetUserId) + "|"
                + (reason == null ? "" : reason.trim()) + "|" + new java.util.TreeMap<>(input);
        return new PlatformSystemAdministrationCommandApi.CommandRequest(commandId,
                sha256(idempotencyKey == null ? "" : idempotencyKey.trim()), operation,
                sha256(canonical), details.actorId(), targetUserId, reason, input);
    }

    private static SystemAdminAuthenticationDetails details(
            Authentication authentication, UUID commandId) {
        if (!(authentication.getDetails() instanceof SystemAdminAuthenticationDetails details)
                || details.commandId() == null
                || !commandId.toString().equals(details.commandId())) {
            throw new com.spaceagent.shared.exception.BusinessException(
                    "System administration command identity is invalid",
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "SYSTEM_ADMIN_COMMAND_ID_MISMATCH");
        }
        return details;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash administrator command", error);
        }
    }

    private static void put(Map<String, String> values, String name, String value) {
        if (value != null && !value.isBlank()) values.put(name, value.trim());
    }

    private static long windowHours(String value) {
        return switch (window(value)) { case "7d" -> 168; case "30d" -> 720; default -> 24; };
    }

    private static String window(String value) {
        if (value == null) return "24h";
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "7d" -> "7d";
            case "30d" -> "30d";
            default -> "24h";
        };
    }

    public record HealthProjection(String status, String releaseVersion, int schemaVersion,
                                   Instant generatedAt) {
    }

    public record OverviewProjection(
            String window,
            Instant generatedAt,
            String releaseVersion,
            int schemaVersion,
            String platformReadiness,
            IdentitySystemAdministrationApi.IdentityOverview identity,
            InferenceSystemAdministrationApi.InferenceOverview inference,
            AgentSystemAdministrationApi.AgentOverview agent,
            ProjectSystemAdministrationApi.ProjectOverview project,
            ConversationSystemAdministrationApi.ConversationOverview conversation,
            RuntimeSystemAdministrationApi.RuntimeOverview runtime,
            ToolingSystemAdministrationApi.ToolingOverview tooling) {
    }

    public record CredentialItem(
            String kind,
            String id,
            String organizationId,
            String ownerUserId,
            String subjectId,
            String name,
            String baseUrl,
            String endpointHost,
            String authType,
            boolean secretConfigured,
            String secretHint,
            String secretFingerprint,
            String secretKeyVersion,
            String status,
            Instant observedAt,
            Map<String, Object> metadata) {
        public CredentialItem { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 255) String loginName,
            @Size(max = 120) String displayName,
            @Size(max = 120) String organizationName,
            @Size(max = 63) String organizationSlug,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record CreateOrganizationRequest(
            @NotBlank @Size(max = 36) String ownerUserId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 63) String slug,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record UpdateOrganizationRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 63) String slug,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record OrganizationMemberRoleRequest(
            @NotBlank @Size(max = 36) String userId,
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record OrganizationRoleRequest(
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record TransferOrganizationOwnershipRequest(
            @NotBlank @Size(max = 36) String newOwnerUserId,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record UserCleanupJobProjection(
            UserCleanupApplicationApi.UserCleanupJobView job,
            List<UserCleanupApplicationApi.UserCleanupStepView> steps) {
        public UserCleanupJobProjection {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }
}
