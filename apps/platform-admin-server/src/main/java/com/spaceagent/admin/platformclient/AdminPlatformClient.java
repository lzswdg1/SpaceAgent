package com.spaceagent.admin.platformclient;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class AdminPlatformClient {
    private static final String INTERNAL_PATH = "/internal/system-admin/v1";
    private static final String HEALTH_ACTOR = "00000000-0000-0000-0000-000000000000";

    private final AdminPlatformClientProperties properties;
    private final AdminPlatformServiceTokenIssuer tokenIssuer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI baseUri;

    public AdminPlatformClient(
            AdminPlatformClientProperties properties,
            AdminPlatformServiceTokenIssuer tokenIssuer,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokenIssuer = tokenIssuer;
        this.objectMapper = objectMapper;
        this.baseUri = validateBaseUri(properties);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positive(properties.getConnectTimeoutSeconds(), "connect timeout")))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        positive(properties.getRequestTimeoutSeconds(), "request timeout");
        if (properties.getMaximumResponseBytes() < 1024 || properties.getMaximumResponseBytes() > 5_000_000) {
            throw new IllegalStateException("Admin platform response limit must be between 1KB and 5MB");
        }
    }

    public PlatformAdminWire.Health health() {
        return get("/health", Map.of(), HEALTH_ACTOR, "system-admin:health:read",
                UUID.randomUUID().toString(), PlatformAdminWire.Health.class);
    }
    public BusinessEvidenceWire.AuditPage businessAudit(Map<String,String> filters,String actor,String request){
        return get("/business-audit",filters,actor,"system-admin:business-evidence:read",request,BusinessEvidenceWire.AuditPage.class);}
    public java.util.List<BusinessEvidenceWire.Capability> resourceCapabilities(String actor,String request){
        return get("/resource-capabilities",Map.of(),actor,"system-admin:business-evidence:read",request,
            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class,BusinessEvidenceWire.Capability.class));}
    public BusinessEvidenceWire.ReviewPage agentChangeEvidence(Map<String,String> filters,String actor,String request){
        return get("/agent-change-evidence",filters,actor,"system-admin:business-evidence:read",request,BusinessEvidenceWire.ReviewPage.class);}
    public BusinessEvidenceWire.UsageOverview usageSummary(Map<String,String> filters,String actor,String request){
        return get("/usage/summary",filters,actor,"system-admin:usage:read",request,BusinessEvidenceWire.UsageOverview.class);}
    public BusinessEvidenceWire.UsageHistory usageHistory(Map<String,String> filters,String actor,String request){
        return get("/usage/history",filters,actor,"system-admin:usage:read",request,BusinessEvidenceWire.UsageHistory.class);}
    public BusinessEvidenceWire.ResourceObservations resourceObservations(Map<String,String> filters,String actor,String request){
        return get("/resource-observations",filters,actor,"system-admin:resources:observations:read",request,BusinessEvidenceWire.ResourceObservations.class);}

    public PlatformAdminWire.Overview overview(
            String window, String actorId, String requestId) {
        return get("/overview", Map.of("window", window), actorId,
                "system-admin:overview:read", requestId, PlatformAdminWire.Overview.class);
    }

    public PlatformAdminWire.Page<PlatformAdminWire.UserSummary> users(
            int page, int pageSize, String query, String status, String sort,
            String actorId, String requestId) {
        JavaType target = pageType(PlatformAdminWire.UserSummary.class);
        return get("/users", params(page, pageSize, query, status, sort), actorId,
                "system-admin:users:read", requestId, target);
    }

    public PlatformAdminWire.UserDetail user(String userId, String actorId, String requestId) {
        return get("/users/" + userId, Map.of(), actorId,
                "system-admin:users:read", requestId, PlatformAdminWire.UserDetail.class);
    }

    public PlatformAdminWire.Page<PlatformAdminWire.CredentialItem> userProviders(
            String userId, int page, int pageSize, String actorId, String requestId) {
        return get("/users/" + userId + "/providers",
                Map.of("page", Integer.toString(page), "pageSize", Integer.toString(pageSize)),
                actorId, "system-admin:users:providers:read", requestId,
                pageType(PlatformAdminWire.CredentialItem.class));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.AgentSummary> userAgents(
            String userId, int page, int pageSize, String actorId, String requestId) {
        return get("/users/" + userId + "/agents",
                Map.of("page", Integer.toString(page), "pageSize", Integer.toString(pageSize)),
                actorId, "system-admin:users:agents:read", requestId,
                pageType(PlatformAdminWire.AgentSummary.class));
    }

    public PlatformAdminWire.UserResourceOverview userResourceOverview(
            String userId, String actorId, String requestId) {
        return get("/users/" + userId + "/resource-overview", Map.of(), actorId,
                "system-admin:users:resources:read", requestId,
                PlatformAdminWire.UserResourceOverview.class);
    }

    public PlatformAdminWire.Page<PlatformAdminWire.UserResourceSummary> userResources(
            String userId, String kind, int page, int pageSize,
            String actorId, String requestId) {
        return get("/users/" + userId + "/resources",
                Map.of("kind", kind, "page", Integer.toString(page),
                        "pageSize", Integer.toString(pageSize)), actorId,
                "system-admin:users:resources:read", requestId,
                pageType(PlatformAdminWire.UserResourceSummary.class));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.OrganizationSummary> organizations(
            int page, int pageSize, String query, String status, String actorId, String requestId) {
        JavaType target = pageType(PlatformAdminWire.OrganizationSummary.class);
        return get("/organizations", params(page, pageSize, query, status, null), actorId,
                "system-admin:organizations:read", requestId, target);
    }

    public PlatformAdminWire.Page<PlatformAdminWire.OrganizationMemberSummary> organizationMembers(
            String organizationId, int page, int pageSize, String query, String status, String role,
            String actorId, String requestId) {
        return get("/organizations/" + organizationId + "/members",
                memberParams(page, pageSize, query, status, role), actorId,
                "system-admin:organizations:members:read", requestId,
                pageType(PlatformAdminWire.OrganizationMemberSummary.class));
    }

    public PlatformAdminWire.CommandView createOrganization(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String ownerUserId, String name, String slug, String reason) {
        return write("POST", "/organizations", actorId,
                "system-admin:organizations:create", requestId, commandId, idempotencyKey,
                Map.of("ownerUserId", ownerUserId, "name", name, "slug", slug,
                        "reason", reason));
    }

    public PlatformAdminWire.CommandView updateOrganization(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String organizationId, String name, String slug, String reason) {
        return write("PATCH", "/organizations/" + organizationId, actorId,
                "system-admin:organizations:update", requestId, commandId, idempotencyKey,
                Map.of("name", name, "slug", slug, "reason", reason));
    }

    public PlatformAdminWire.CommandView deleteOrganization(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String organizationId, String reason) {
        return write("POST", "/organizations/" + organizationId + "/deletion-jobs", actorId,
                "system-admin:organizations:delete", requestId, commandId, idempotencyKey,
                Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView addOrganizationMember(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String organizationId, String userId, String role, String reason) {
        return write("POST", "/organizations/" + organizationId + "/members", actorId,
                "system-admin:organizations:members:add", requestId, commandId, idempotencyKey,
                Map.of("userId", userId, "role", role, "reason", reason));
    }

    public PlatformAdminWire.CommandView updateOrganizationMemberRole(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String organizationId, String userId, String role, String reason) {
        return write("PATCH", "/organizations/" + organizationId + "/members/" + userId,
                actorId, "system-admin:organizations:members:role-update", requestId,
                commandId, idempotencyKey, Map.of("role", role, "reason", reason));
    }

    public PlatformAdminWire.CommandView removeOrganizationMember(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String organizationId, String userId, String reason) {
        return write("DELETE", "/organizations/" + organizationId + "/members/" + userId,
                actorId, "system-admin:organizations:members:remove", requestId,
                commandId, idempotencyKey, Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView transferOrganizationOwnership(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String organizationId, String newOwnerUserId, String reason) {
        return write("POST", "/organizations/" + organizationId + "/ownership-transfers",
                actorId, "system-admin:organizations:owner-transfer", requestId,
                commandId, idempotencyKey,
                Map.of("newOwnerUserId", newOwnerUserId, "reason", reason));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.CredentialItem> credentials(
            String kind, int page, int pageSize, String actorId, String requestId) {
        JavaType target = pageType(PlatformAdminWire.CredentialItem.class);
        return get("/credential-inventory", Map.of("kind", kind, "page", Integer.toString(page),
                "pageSize", Integer.toString(pageSize)), actorId,
                "system-admin:credentials:read", requestId, target);
    }

    public PlatformAdminWire.PresenceSummary presence(String actorId, String requestId) {
        return get("/presence", Map.of(), actorId, "system-admin:presence:read", requestId,
                PlatformAdminWire.PresenceSummary.class);
    }

    public PlatformAdminWire.CommandView createUser(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String loginName, String displayName, String organizationName,
            String organizationSlug, String reason) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("loginName", loginName);
        put(body, "displayName", displayName);
        put(body, "organizationName", organizationName);
        put(body, "organizationSlug", organizationSlug);
        body.put("reason", reason);
        return post("/users", actorId, "system-admin:users:create", requestId,
                commandId, idempotencyKey, body);
    }

    public PlatformAdminWire.CommandView updateUser(UUID commandId, String idempotencyKey,
            String actorId, String requestId, String userId, String displayName, String reason) {
        return write("PATCH", "/users/" + userId, actorId, "system-admin:users:update", requestId,
                commandId, idempotencyKey, Map.of("displayName", displayName, "reason", reason));
    }

    public PlatformAdminWire.CommandView revokeUserSessions(UUID commandId, String idempotencyKey,
            String actorId, String requestId, String userId, String reason) {
        return post("/users/" + userId + "/session-revocations", actorId, "system-admin:users:sessions:revoke",
                requestId, commandId, idempotencyKey, Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView resetUserPassword(UUID commandId, String idempotencyKey,
            String actorId, String requestId, String userId, String reason) {
        return post("/users/" + userId + "/password-resets", actorId, "system-admin:users:password:reset",
                requestId, commandId, idempotencyKey, Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView suspendUser(UUID commandId, String idempotencyKey,
            String actorId, String requestId, String userId, String reason) {
        return post("/users/" + userId + "/suspend", actorId, "system-admin:users:suspend",
                requestId, commandId, idempotencyKey, Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView restoreUser(UUID commandId, String idempotencyKey,
            String actorId, String requestId, String userId, String reason) {
        return post("/users/" + userId + "/restore", actorId, "system-admin:users:restore",
                requestId, commandId, idempotencyKey, Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView deletionPreflight(UUID commandId, String idempotencyKey,
            String actorId, String requestId, String userId, String reason) {
        return post("/users/" + userId + "/deletion-requests", actorId,
                "system-admin:users:deletion-preflight", requestId, commandId,
                idempotencyKey, Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView requestDeletion(UUID commandId, String idempotencyKey,
            String actorId, String requestId, String userId, String reason) {
        return post("/users/" + userId + "/deletion-jobs", actorId,
                "system-admin:users:delete", requestId, commandId, idempotencyKey,
                Map.of("reason", reason));
    }

    public PlatformAdminWire.UserCleanupJobProjection userCleanupJob(
            String userId, String actorId, String requestId) {
        return get("/users/" + userId + "/deletion-jobs/current", Map.of(), actorId,
                "system-admin:users:deletion:read", requestId,
                PlatformAdminWire.UserCleanupJobProjection.class);
    }

    public PlatformAdminWire.CommandView command(UUID commandId, String actorId, String requestId) {
        return get("/commands/" + commandId, Map.of(), actorId,
                "system-admin:commands:read", requestId, PlatformAdminWire.CommandView.class);
    }

    public PlatformAdminWire.Page<PlatformAdminWire.CleanupJobSummary> cleanupJobs(
            int page, int pageSize, String kind, String state, String query,
            String actorId, String requestId) {
        return get("/cleanup-jobs", cleanupParams(page, pageSize, kind, state, query), actorId,
                "system-admin:cleanup:read", requestId,
                pageType(PlatformAdminWire.CleanupJobSummary.class));
    }

    public PlatformAdminWire.CleanupJobDetail cleanupJob(
            String kind, String subjectId, String actorId, String requestId) {
        return get("/cleanup-jobs/" + kind + "/" + subjectId, Map.of(), actorId,
                "system-admin:cleanup:read", requestId, PlatformAdminWire.CleanupJobDetail.class);
    }

    public PlatformAdminWire.CleanupOverview cleanupOverview(String actorId, String requestId) {
        return get("/cleanup-jobs/overview", Map.of(), actorId, "system-admin:cleanup:read",
                requestId, PlatformAdminWire.CleanupOverview.class);
    }

    public PlatformAdminWire.Page<PlatformAdminWire.McpRegistrySyncJob> mcpRegistrySyncJobs(
            int page, int pageSize, String state, String actorId, String requestId) {
        return get("/mcp-registry/sync-jobs",
                params(page, pageSize, null, state, null), actorId,
                "system-admin:mcp-registry:read", requestId,
                pageType(PlatformAdminWire.McpRegistrySyncJob.class));
    }

    public PlatformAdminWire.Page<PlatformAdminWire.McpRegistryCandidate> mcpRegistryCandidates(
            int page, int pageSize, String state, String query,
            String actorId, String requestId) {
        return get("/mcp-registry/candidates",
                params(page, pageSize, query, state, null), actorId,
                "system-admin:mcp-registry:read", requestId,
                pageType(PlatformAdminWire.McpRegistryCandidate.class));
    }

    public PlatformAdminWire.McpRegistryCandidateDetail mcpRegistryCandidate(
            String candidateId, String actorId, String requestId) {
        return get("/mcp-registry/candidates/" + candidateId, Map.of(), actorId,
                "system-admin:mcp-registry:read", requestId,
                PlatformAdminWire.McpRegistryCandidateDetail.class);
    }

    public PlatformAdminWire.CommandView requestMcpRegistrySync(
            UUID commandId, String idempotencyKey, String actorId,
            String requestId, String reason) {
        return post("/mcp-registry/sync-jobs", actorId,
                "system-admin:mcp-registry:sync", requestId, commandId,
                idempotencyKey, Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView approveMcpRegistryCandidate(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String candidateId, String reason) {
        return post("/mcp-registry/candidates/" + candidateId + "/approvals", actorId,
                "system-admin:mcp-registry:approve", requestId, commandId,
                idempotencyKey, Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView rejectMcpRegistryCandidate(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String candidateId, String reason) {
        return post("/mcp-registry/candidates/" + candidateId + "/rejections", actorId,
                "system-admin:mcp-registry:reject", requestId, commandId,
                idempotencyKey, Map.of("reason", reason));
    }

    public PlatformAdminWire.CommandView retryArtifactDeletion(
            UUID commandId, String idempotencyKey, String actorId, String requestId,
            String objectId, String tenantId, String reason) {
        return post("/artifact-objects/" + objectId + "/deletion-retries", actorId,
                "system-admin:artifacts:deletion:retry", requestId, commandId,
                idempotencyKey, Map.of("tenantId", tenantId, "reason", reason));
    }

    private <T> T get(String path, Map<String, String> parameters, String actorId, String scope,
                      String requestId, Class<T> target) {
        return get(path, parameters, actorId, scope, requestId,
                objectMapper.getTypeFactory().constructType(target));
    }

    private PlatformAdminWire.CommandView post(
            String path, String actorId, String scope, String requestId, UUID commandId,
            String idempotencyKey, Map<String, Object> body) {
        return write("POST", path, actorId, scope, requestId, commandId, idempotencyKey, body);
    }

    private PlatformAdminWire.CommandView write(
            String method, String path, String actorId, String scope, String requestId,
            UUID commandId, String idempotencyKey, Map<String, Object> body) {
        try {
            URI uri = UriComponentsBuilder.fromUri(baseUri).path(INTERNAL_PATH + path)
                    .build().encode().toUri();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer "
                            + tokenIssuer.issue(actorId, scope, requestId, commandId))
                    .header("X-Request-ID", requestId)
                    .header("X-Admin-Command-ID", commandId.toString())
                    .header("Idempotency-Key", idempotencyKey)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(
                            objectMapper.writeValueAsBytes(body)))
                    .build();
            return readResponse(httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream()),
                    objectMapper.getTypeFactory().constructType(PlatformAdminWire.CommandView.class));
        } catch (AdminPlatformClientException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AdminPlatformClientException("PLATFORM_INTERRUPTED",
                    "Platform administrator command was interrupted", error);
        } catch (Exception error) {
            throw new AdminPlatformClientException("PLATFORM_COMMAND_UNKNOWN",
                    "Platform administrator command outcome is unknown", error);
        }
    }

    private <T> T get(String path, Map<String, String> parameters, String actorId, String scope,
                      String requestId, JavaType target) {
        try {
            var builder = UriComponentsBuilder.fromUri(baseUri).path(INTERNAL_PATH + path);
            parameters.forEach((name, value) -> {
                if (value != null && !value.isBlank()) builder.queryParam(name, value);
            });
            HttpRequest request = HttpRequest.newBuilder(builder.build().encode().toUri())
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + tokenIssuer.issue(actorId, scope, requestId))
                    .header("X-Request-ID", requestId)
                    .GET().build();
            return readResponse(httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream()), target);
        } catch (AdminPlatformClientException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AdminPlatformClientException("PLATFORM_INTERRUPTED",
                    "Platform administration request was interrupted", error);
        } catch (Exception error) {
            throw new AdminPlatformClientException("PLATFORM_UNAVAILABLE",
                    "Platform administration service is unavailable", error);
        }
    }

    private <T> T readResponse(HttpResponse<InputStream> response, JavaType target) throws Exception {
        try (InputStream body = response.body()) {
            byte[] payload = body.readNBytes(properties.getMaximumResponseBytes() + 1);
            if (payload.length > properties.getMaximumResponseBytes()) {
                throw new AdminPlatformClientException(
                        "PLATFORM_RESPONSE_TOO_LARGE", "Platform response exceeded its bound");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AdminPlatformClientException(
                        response.statusCode() == 404 ? "PLATFORM_RESOURCE_NOT_FOUND" : "PLATFORM_UNAVAILABLE",
                        "Platform administration request failed");
            }
            JavaType envelopeType = objectMapper.getTypeFactory()
                    .constructParametricType(PlatformAdminWire.Envelope.class, target);
            PlatformAdminWire.Envelope<T> envelope = objectMapper.readValue(payload, envelopeType);
            if (envelope == null || !envelope.success() || envelope.data() == null) {
                throw new AdminPlatformClientException(
                        envelope == null || envelope.code() == null ? "PLATFORM_RESPONSE_INVALID" : envelope.code(),
                        "Platform administration response was invalid");
            }
            return envelope.data();
        }
    }

    private static void put(Map<String, Object> body, String name, String value) {
        if (value != null && !value.isBlank()) body.put(name, value.trim());
    }

    private JavaType pageType(Class<?> itemType) {
        return objectMapper.getTypeFactory().constructParametricType(PlatformAdminWire.Page.class, itemType);
    }

    private static Map<String, String> params(
            int page, int pageSize, String query, String status, String sort) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        values.put("page", Integer.toString(Math.max(0, page)));
        values.put("pageSize", Integer.toString(Math.max(1, Math.min(100, pageSize))));
        values.put("query", query);
        values.put("status", status);
        values.put("sort", sort);
        return values;
    }

    private static Map<String, String> memberParams(
            int page, int pageSize, String query, String status, String role) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        values.put("page", Integer.toString(Math.max(0, page)));
        values.put("pageSize", Integer.toString(Math.max(1, Math.min(100, pageSize))));
        values.put("query", query);
        values.put("status", status);
        values.put("role", role);
        return values;
    }

    private static Map<String, String> cleanupParams(
            int page, int pageSize, String kind, String state, String query) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        values.put("page", Integer.toString(Math.max(0, page)));
        values.put("pageSize", Integer.toString(Math.max(1, Math.min(100, pageSize))));
        values.put("kind", kind); values.put("state", state); values.put("query", query);
        return values;
    }

    private static URI validateBaseUri(AdminPlatformClientProperties properties) {
        URI uri = URI.create(properties.getBaseUrl());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException("Admin platform base URL is invalid");
        }
        boolean localHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost())
                || "platform-server".equalsIgnoreCase(uri.getHost()));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !(properties.isAllowInsecureLocal() && localHttp)) {
            throw new IllegalStateException(
                    "Admin platform client requires HTTPS/mTLS unless insecure local mode is explicit");
        }
        return uri;
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalStateException("Admin platform " + name + " must be positive");
        return value;
    }
}
