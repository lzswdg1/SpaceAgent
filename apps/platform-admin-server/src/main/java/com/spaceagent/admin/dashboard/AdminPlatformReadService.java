package com.spaceagent.admin.dashboard;

import com.spaceagent.admin.audit.application.AdminAuditService;
import com.spaceagent.admin.audit.domain.AdminAuditOutcome;
import com.spaceagent.admin.platformclient.AdminPlatformClient;
import com.spaceagent.admin.platformclient.AdminPlatformClientException;
import com.spaceagent.admin.platformclient.PlatformAdminWire;
import com.spaceagent.admin.security.AdminTokenMaterial;
import com.spaceagent.admin.shared.AdminApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class AdminPlatformReadService {
    private final AdminPlatformClient client;
    private final AdminAuditService audit;
    private final Clock clock;
    private final Map<String, CachedOverview> overviewCache = new ConcurrentHashMap<>();

    public AdminPlatformReadService(
            AdminPlatformClient client,
            AdminAuditService audit,
            Clock adminClock) {
        this.client = client;
        this.audit = audit;
        this.clock = adminClock;
    }

    public DashboardView dashboard(String window, UUID actor, UUID session, String requestId) {
        String normalized = switch (window == null ? "" : window.toLowerCase()) {
            case "7d" -> "7d";
            case "30d" -> "30d";
            default -> "24h";
        };
        String hash = AdminTokenMaterial.sha256("dashboard:" + normalized);
        try {
            PlatformAdminWire.Overview overview = client.overview(normalized, actor.toString(), requestId);
            overviewCache.put(normalized, new CachedOverview(overview, clock.instant()));
            audit.append(actor, session, "ADMIN_DASHBOARD_READ", "PLATFORM", null,
                    requestId, hash, AdminAuditOutcome.SUCCEEDED, null);
            return new DashboardView(overview, false, null, clock.instant());
        } catch (AdminPlatformClientException error) {
            CachedOverview cached = overviewCache.get(normalized);
            audit.append(actor, session, "ADMIN_DASHBOARD_READ", "PLATFORM", null,
                    requestId, hash, cached == null ? AdminAuditOutcome.FAILED : AdminAuditOutcome.UNKNOWN,
                    error.safeCode());
            if (cached != null && cached.cachedAt().isAfter(clock.instant().minus(5, ChronoUnit.MINUTES))) {
                return new DashboardView(cached.overview(), true, error.safeCode(), clock.instant());
            }
            throw unavailable(error);
        }
    }

    public PlatformAdminWire.Page<PlatformAdminWire.UserSummary> users(
            int page, int pageSize, String query, String status, String sort,
            UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_USERS_READ", "PLATFORM_USER", null,
                "users:" + page + ":" + pageSize + ":" + query + ":" + status + ":" + sort,
                () -> client.users(page, pageSize, query, status, sort, actor.toString(), requestId));
    }

    public PlatformAdminWire.PresenceSummary presence(UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_PRESENCE_READ", "PLATFORM", null,
                "presence", () -> client.presence(actor.toString(), requestId));
    }

    public PlatformAdminWire.UserDetail user(
            String userId, UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_USER_READ", "PLATFORM_USER", userId,
                "user:" + userId, () -> client.user(userId, actor.toString(), requestId));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.CredentialItem> userProviders(
            String userId, int page, int pageSize, UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_USER_PROVIDERS_READ", "PLATFORM_USER",
                userId, "user-providers:" + userId + ":" + page + ":" + pageSize,
                () -> client.userProviders(userId, page, pageSize, actor.toString(), requestId));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.AgentSummary> userAgents(
            String userId, int page, int pageSize, UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_USER_AGENTS_READ", "PLATFORM_USER",
                userId, "user-agents:" + userId + ":" + page + ":" + pageSize,
                () -> client.userAgents(userId, page, pageSize, actor.toString(), requestId));
    }

    public PlatformAdminWire.UserResourceOverview userResourceOverview(
            String userId, UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_USER_RESOURCE_OVERVIEW_READ",
                "PLATFORM_USER", userId, "user-resource-overview:" + userId,
                () -> client.userResourceOverview(userId, actor.toString(), requestId));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.UserResourceSummary> userResources(
            String userId, String kind, int page, int pageSize,
            UUID actor, UUID session, String requestId) {
        String safeKind = resourceKind(kind);
        return audited(actor, session, requestId, "ADMIN_USER_RESOURCES_READ", "PLATFORM_USER",
                userId, "user-resources:" + userId + ":" + safeKind + ":" + page + ":" + pageSize,
                () -> client.userResources(userId, safeKind, page, pageSize,
                        actor.toString(), requestId));
    }

    private static String resourceKind(String value) {
        if (value == null || value.isBlank()) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST,
                    "ADMIN_USER_RESOURCE_KIND_INVALID", "User resource kind is invalid");
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("MODEL_POOL", "PROJECT", "TASK", "WORKSPACE", "CONVERSATION",
                "MCP_CONNECTION", "KNOWLEDGE_DOCUMENT", "MEMORY", "AUTOMATION", "RUN",
                "MODEL_EFFECT", "TOOL_EFFECT").contains(normalized)) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST,
                    "ADMIN_USER_RESOURCE_KIND_INVALID", "User resource kind is invalid");
        }
        return normalized;
    }

    public PlatformAdminWire.Page<PlatformAdminWire.OrganizationSummary> organizations(
            int page, int pageSize, String query, String status,
            UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_ORGANIZATIONS_READ", "ORGANIZATION", null,
                "organizations:" + page + ":" + pageSize + ":" + query + ":" + status,
                () -> client.organizations(page, pageSize, query, status, actor.toString(), requestId));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.OrganizationMemberSummary> organizationMembers(
            String organizationId, int page, int pageSize, String query, String status, String role,
            UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_ORGANIZATION_MEMBERS_READ",
                "ORGANIZATION", organizationId,
                "organization-members:" + organizationId + ":" + page + ":" + pageSize
                        + ":" + query + ":" + status + ":" + role,
                () -> client.organizationMembers(organizationId, page, pageSize, query, status,
                        role, actor.toString(), requestId));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.CredentialItem> credentials(
            String kind, int page, int pageSize, UUID actor, UUID session, String requestId) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("provider", "agent-key", "mcp").contains(normalized)) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST,
                    "ADMIN_CREDENTIAL_KIND_INVALID", "Credential inventory kind is invalid");
        }
        return audited(actor, session, requestId, "ADMIN_CREDENTIAL_INVENTORY_READ", "CREDENTIAL", normalized,
                "credentials:" + normalized + ":" + page + ":" + pageSize,
                () -> client.credentials(normalized, page, pageSize, actor.toString(), requestId));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.CleanupJobSummary> cleanupJobs(
            int page, int pageSize, String kind, String state, String query,
            UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_CLEANUP_JOBS_READ", "CLEANUP_JOB", null,
                "cleanup-jobs:" + page + ":" + pageSize + ":" + kind + ":" + state + ":" + query,
                () -> client.cleanupJobs(page, pageSize, kind, state, query,
                        actor.toString(), requestId));
    }

    public PlatformAdminWire.CleanupJobDetail cleanupJob(
            String kind, String subjectId, UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_CLEANUP_JOB_READ", "CLEANUP_JOB",
                kind + ":" + subjectId, "cleanup-job:" + kind + ":" + subjectId,
                () -> client.cleanupJob(kind, subjectId, actor.toString(), requestId));
    }

    public PlatformAdminWire.CleanupOverview cleanupOverview(
            UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_CLEANUP_OVERVIEW_READ", "CLEANUP_JOB",
                null, "cleanup-overview",
                () -> client.cleanupOverview(actor.toString(), requestId));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.McpRegistrySyncJob> mcpRegistrySyncJobs(
            int page, int pageSize, String state,
            UUID actor, UUID session, String requestId) {
        String normalizedState = registryState(state,
                java.util.Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED"));
        return audited(actor, session, requestId, "ADMIN_MCP_REGISTRY_SYNC_JOBS_READ",
                "MCP_REGISTRY", null,
                "mcp-registry-sync-jobs:" + page + ":" + pageSize + ":" + normalizedState,
                () -> client.mcpRegistrySyncJobs(page, pageSize, normalizedState,
                        actor.toString(), requestId));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.McpRegistryCandidate> mcpRegistryCandidates(
            int page, int pageSize, String state, String query,
            UUID actor, UUID session, String requestId) {
        String normalizedState = registryState(state,
                java.util.Set.of("PENDING_REVIEW", "APPROVED", "REJECTED"));
        return audited(actor, session, requestId, "ADMIN_MCP_REGISTRY_CANDIDATES_READ",
                "MCP_REGISTRY_CANDIDATE", null,
                "mcp-registry-candidates:" + page + ":" + pageSize + ":" + normalizedState
                        + ":" + query,
                () -> client.mcpRegistryCandidates(page, pageSize, normalizedState, query,
                        actor.toString(), requestId));
    }

    public PlatformAdminWire.McpRegistryCandidateDetail mcpRegistryCandidate(
            String candidateId, UUID actor, UUID session, String requestId) {
        return audited(actor, session, requestId, "ADMIN_MCP_REGISTRY_CANDIDATE_READ",
                "MCP_REGISTRY_CANDIDATE", candidateId, "mcp-registry-candidate:" + candidateId,
                () -> client.mcpRegistryCandidate(candidateId, actor.toString(), requestId));
    }

    public com.spaceagent.admin.platformclient.BusinessEvidenceWire.AuditPage businessAudit(Map<String,String> filters,UUID actor,UUID session,String request){
        return audited(actor,session,request,"ADMIN_BUSINESS_AUDIT_READ","BUSINESS_EVIDENCE",null,filters.toString(),()->client.businessAudit(filters,actor.toString(),request));}
    public java.util.List<com.spaceagent.admin.platformclient.BusinessEvidenceWire.Capability> resourceCapabilities(UUID actor,UUID session,String request){
        return audited(actor,session,request,"ADMIN_CAPABILITIES_READ","RESOURCE_POLICY",null,"capabilities",()->client.resourceCapabilities(actor.toString(),request));}
    public com.spaceagent.admin.platformclient.BusinessEvidenceWire.ReviewPage agentChangeEvidence(Map<String,String> filters,UUID actor,UUID session,String request){
        return audited(actor,session,request,"ADMIN_AGENT_CHANGE_EVIDENCE_READ","AGENT",null,filters.toString(),()->client.agentChangeEvidence(filters,actor.toString(),request));}
    public com.spaceagent.admin.platformclient.BusinessEvidenceWire.UsageOverview usageSummary(Map<String,String> filters,UUID actor,UUID session,String request){
        return audited(actor,session,request,"ADMIN_USAGE_SUMMARY_READ","USAGE",null,filters.toString(),()->client.usageSummary(filters,actor.toString(),request));}
    public com.spaceagent.admin.platformclient.BusinessEvidenceWire.ResourceObservations resourceObservations(Map<String,String> filters,UUID actor,UUID session,String request){
        return audited(actor,session,request,"ADMIN_RESOURCE_OBSERVATIONS_READ","RESOURCE",null,filters.toString(),()->client.resourceObservations(filters,actor.toString(),request));}
    public com.spaceagent.admin.platformclient.BusinessEvidenceWire.UsageHistory usageHistory(Map<String,String> filters,UUID actor,UUID session,String request){
        return audited(actor,session,request,"ADMIN_USAGE_HISTORY_READ","USAGE",null,filters.toString(),()->client.usageHistory(filters,actor.toString(),request));}
    private <T> T audited(UUID actor, UUID session, String requestId, String action,
                          String targetType, String targetId, String input, Supplier<T> call) {
        String hash = AdminTokenMaterial.sha256(input);
        try {
            T result = call.get();
            audit.append(actor, session, action, targetType, targetId, requestId, hash,
                    AdminAuditOutcome.SUCCEEDED, null);
            return result;
        } catch (AdminPlatformClientException error) {
            audit.append(actor, session, action, targetType, targetId, requestId, hash,
                    AdminAuditOutcome.FAILED, error.safeCode());
            if ("PLATFORM_RESOURCE_NOT_FOUND".equals(error.safeCode())) {
                throw new AdminApiException(HttpStatus.NOT_FOUND, "ADMIN_RESOURCE_NOT_FOUND",
                        "Requested platform resource was not found");
            }
            throw unavailable(error);
        }
    }

    private static String registryState(String value, java.util.Set<String> allowed) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST,
                    "ADMIN_MCP_REGISTRY_STATE_INVALID", "MCP Registry state is invalid");
        }
        return normalized;
    }

    private AdminApiException unavailable(AdminPlatformClientException error) {
        return new AdminApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "ADMIN_PLATFORM_UNAVAILABLE", "Platform administration data is unavailable");
    }

    public record DashboardView(PlatformAdminWire.Overview projection, boolean stale,
                                String staleReasonCode, Instant servedAt) {
    }
    private record CachedOverview(PlatformAdminWire.Overview overview, Instant cachedAt) {
    }
}
