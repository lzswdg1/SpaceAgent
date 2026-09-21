package com.spaceagent.admin.dashboard;

import com.spaceagent.admin.audit.application.AdminEvidenceReadService;
import com.spaceagent.admin.platformclient.PlatformAdminWire;
import com.spaceagent.admin.shared.AdminApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/v1")
public class AdminPlatformReadController {
    private final AdminPlatformReadService platformReads;
    private final AdminEvidenceReadService evidenceReads;

    public AdminPlatformReadController(
            AdminPlatformReadService platformReads,
            AdminEvidenceReadService evidenceReads) {
        this.platformReads = platformReads;
        this.evidenceReads = evidenceReads;
    }

    @GetMapping("/dashboard")
    public AdminApiResponse<AdminPlatformReadService.DashboardView> dashboard(
            @RequestParam(defaultValue = "24h") String window,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.dashboard(window, actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/presence")
    public AdminApiResponse<PlatformAdminWire.PresenceSummary> presence(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.presence(actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/users")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.UserSummary>> users(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sort,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.users(page, pageSize, query, status, sort,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/users/{userId}")
    public AdminApiResponse<PlatformAdminWire.UserDetail> user(
            @PathVariable String userId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.user(userId, actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/users/{userId}/providers")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.CredentialItem>> userProviders(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.userProviders(userId, page, pageSize,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/users/{userId}/agents")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.AgentSummary>> userAgents(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.userAgents(userId, page, pageSize,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/users/{userId}/resource-overview")
    public AdminApiResponse<PlatformAdminWire.UserResourceOverview> userResourceOverview(
            @PathVariable String userId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.userResourceOverview(userId,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/users/{userId}/resources")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.UserResourceSummary>>
            userResources(
            @PathVariable String userId,
            @RequestParam String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.userResources(userId, kind, page, pageSize,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/organizations")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.OrganizationSummary>> organizations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.organizations(page, pageSize, query, status,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/organizations/{organizationId}/members")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.OrganizationMemberSummary>>
            organizationMembers(
            @PathVariable String organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.organizationMembers(organizationId, page, pageSize,
                query, status, role, actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/credential-inventory")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.CredentialItem>> credentials(
            @RequestParam String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.credentials(kind, page, pageSize,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/cleanup-jobs")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.CleanupJobSummary>> cleanupJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String query,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.cleanupJobs(page, pageSize, kind, state, query,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/cleanup-jobs/overview")
    public AdminApiResponse<PlatformAdminWire.CleanupOverview> cleanupOverview(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.cleanupOverview(actor(jwt), session(jwt),
                requestId(requestId)));
    }

    @GetMapping("/cleanup-jobs/{kind}/{subjectId}")
    public AdminApiResponse<PlatformAdminWire.CleanupJobDetail> cleanupJob(
            @PathVariable String kind,
            @PathVariable String subjectId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.cleanupJob(kind, subjectId, actor(jwt),
                session(jwt), requestId(requestId)));
    }

    @GetMapping("/audit-events")
    public AdminApiResponse<AdminEvidenceReadService.AuditPage> auditEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String target,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(evidenceReads.auditEvents(page, pageSize, actor, action, target,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/mcp-registry/sync-jobs")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.McpRegistrySyncJob>>
            mcpRegistrySyncJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String state,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.mcpRegistrySyncJobs(
                page, pageSize, state, actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/mcp-registry/candidates")
    public AdminApiResponse<PlatformAdminWire.Page<PlatformAdminWire.McpRegistryCandidate>>
            mcpRegistryCandidates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String query,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.mcpRegistryCandidates(
                page, pageSize, state, query, actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/mcp-registry/candidates/{candidateId}")
    public AdminApiResponse<PlatformAdminWire.McpRegistryCandidateDetail> mcpRegistryCandidate(
            @PathVariable String candidateId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(platformReads.mcpRegistryCandidate(
                candidateId, actor(jwt), session(jwt), requestId(requestId)));
    }

    @GetMapping("/commands/{commandId}")
    public AdminApiResponse<AdminEvidenceReadService.CommandView> command(
            @PathVariable UUID commandId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(evidenceReads.command(commandId, actor(jwt), session(jwt),
                requestId(requestId)));
    }

    @GetMapping("/commands")
    public AdminApiResponse<AdminEvidenceReadService.CommandPage> commands(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String target,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return AdminApiResponse.ok(evidenceReads.commands(page, pageSize, state, operation, target,
                actor(jwt), session(jwt), requestId(requestId)));
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static UUID session(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("session_id")); }
    private static String requestId(String value) {
        if (value == null || value.isBlank()) return UUID.randomUUID().toString();
        return value.substring(0, Math.min(120, value.length()));
    }
}
