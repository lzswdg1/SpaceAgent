package com.spaceagent.admin.platformclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPlatformClientTest {
    private static final String SECRET = "admin-platform-client-test-secret-0123456789-abcdef";
    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void resourceObservationsKeepNullAndExactNumericValuesWithDedicatedNonTenantScope() throws Exception {
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var authorization = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var now = Instant.now();
        var expected = new BusinessEvidenceWire.ResourceObservations(2,
                new java.math.BigDecimal("9000000000000000001"), 456L, null, null,
                new java.math.BigDecimal("200"), 0, 0, 2, 0, "PARTIAL", now.minusSeconds(60), now, now);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/system-admin/v1/resource-observations", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            write(exchange, mapper.writeValueAsBytes(Map.of("success", true, "data", expected)));
        });
        server.start();
        var properties = properties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        var client = new AdminPlatformClient(properties, new AdminPlatformServiceTokenIssuer(properties, Clock.systemUTC()), mapper);
        var result = client.resourceObservations(Map.of("organizationId", "org-1", "userId", "user-1"),
                "00000000-0000-0000-0000-000000000123", "resource-request");
        assertThat(result).isEqualTo(expected);
        assertThat(query.get()).contains("organizationId=org-1", "userId=user-1");
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(authorization.get().substring("Bearer ".length())).getPayload();
        assertThat(claims.get("scope", String.class)).isEqualTo("system-admin:resources:observations:read");
        assertThat(claims).doesNotContainKeys("tenant_id", "organization_id", "tenant_role");
    }

    @Test
    void issuesExactScopeTokenAndReadsBoundedVersionedOverview() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/system-admin/v1/overview", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            write(exchange, mapper.writeValueAsBytes(Map.of(
                    "success", true,
                    "code", "OK",
                    "message", "success",
                    "data", overview())));
        });
        server.start();

        AdminPlatformClientProperties properties = properties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        AdminPlatformClient client = new AdminPlatformClient(properties,
                new AdminPlatformServiceTokenIssuer(properties, Clock.systemUTC()), mapper);

        PlatformAdminWire.Overview result = client.overview(
                "24h", "00000000-0000-0000-0000-000000000123", "request-1");

        assertThat(result.schemaVersion()).isEqualTo(1038);
        assertThat(result.identity().totalUsers()).isEqualTo(2);
        String token = authorization.get().substring("Bearer ".length());
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(
                        SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(token).getPayload();
        assertThat(claims.get("scope", String.class)).isEqualTo("system-admin:overview:read");
        assertThat(claims.get("actor_id", String.class))
                .isEqualTo("00000000-0000-0000-0000-000000000123");
        assertThat(claims).doesNotContainKeys("tenant_id", "organization_id", "tenant_role");
        assertThat(java.time.Duration.between(claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant()).toSeconds()).isLessThanOrEqualTo(45);
    }

    @Test void readsTypedEvidenceAndUsageWithExactNonTenantScopes() throws Exception {
        var mapper=JsonMapper.builder().addModule(new JavaTimeModule()).build();var scopes=new java.util.ArrayList<String>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var summary=new BusinessEvidenceWire.UsageSummary("MODEL",1,Map.of("UNKNOWN",1L),null,null,null,"USD",1,1,1,null,0,"KNOWN_SUBTOTALS_ONLY");
        Map<String,Object> payloads=Map.of("/business-audit",new BusinessEvidenceWire.AuditPage(java.util.List.of(),0,"UNCONFIRMED_VISIBLE"),
            "/usage/summary",new BusinessEvidenceWire.UsageOverview(Instant.now().minusSeconds(60),Instant.now(),java.util.List.of(summary),"OWNER_LEDGER"),
            "/usage/history",new BusinessEvidenceWire.UsageHistory(java.util.List.of(),0),
            "/resource-capabilities",java.util.List.of(new BusinessEvidenceWire.Capability("AGENT","READ_ONLY",java.util.List.of(),java.util.List.of("NO_IMPERSONATION"))),
            "/agent-change-evidence",new BusinessEvidenceWire.ReviewPage(java.util.List.of(),0));
        payloads.forEach((path,payload)->server.createContext("/internal/system-admin/v1"+path,x->{
            var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).build().parseSignedClaims(x.getRequestHeaders().getFirst("Authorization").substring(7)).getPayload();
            scopes.add(claims.get("scope",String.class));assertThat(claims).doesNotContainKeys("tenant_id","tenant_role");write(x,mapper.writeValueAsBytes(Map.of("success",true,"data",payload)));}));
        server.start();var p=properties();p.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());var client=new AdminPlatformClient(p,new AdminPlatformServiceTokenIssuer(p,Clock.systemUTC()),mapper);
        String actor=UUID.randomUUID().toString();assertThat(client.businessAudit(Map.of(),actor,"audit").coverage()).isEqualTo("UNCONFIRMED_VISIBLE");
        assertThat(client.usageSummary(Map.of(),actor,"usage").summaries().getFirst().knownCostMicros()).isNull();
        assertThat(client.usageHistory(Map.of("kind","MODEL"),actor,"history").total()).isZero();
        assertThat(client.resourceCapabilities(actor,"capabilities").getFirst().commands()).isEmpty();assertThat(client.agentChangeEvidence(Map.of(),actor,"review").items()).isEmpty();
        assertThat(scopes).containsExactly("system-admin:business-evidence:read","system-admin:usage:read","system-admin:usage:read","system-admin:business-evidence:read","system-admin:business-evidence:read");
    }
    @Test
    void sendsOrganizationPatchWithExactScopeAndCommandEvidence() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> commandHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/system-admin/v1/organizations/org-1", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            commandHeader.set(exchange.getRequestHeaders().getFirst("X-Admin-Command-ID"));
            UUID commandId = UUID.fromString(commandHeader.get());
            write(exchange, mapper.writeValueAsBytes(Map.of(
                    "success", true, "code", "OK", "message", "success",
                    "data", Map.ofEntries(Map.entry("commandId", commandId.toString()),
                            Map.entry("operation", "ORGANIZATION_UPDATE"),
                            Map.entry("targetUserId", "org-1"), Map.entry("state", "SUCCEEDED"),
                            Map.entry("result", Map.of("organizationId", "org-1", "status", "ACTIVE")),
                            Map.entry("createdAt", Instant.now().toString()),
                            Map.entry("updatedAt", Instant.now().toString())))));
        });
        server.start();
        AdminPlatformClientProperties properties = properties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        AdminPlatformClient client = new AdminPlatformClient(properties,
                new AdminPlatformServiceTokenIssuer(properties, Clock.systemUTC()), mapper);
        UUID commandId = UUID.randomUUID();

        PlatformAdminWire.CommandView result = client.updateOrganization(commandId, "key-1",
                "00000000-0000-0000-0000-000000000123", "request-1", "org-1",
                "Renamed", "renamed", "Approved rename");

        assertThat(result.state()).isEqualTo("SUCCEEDED");
        assertThat(method.get()).isEqualTo("PATCH");
        assertThat(commandHeader.get()).isEqualTo(commandId.toString());
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(
                        SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(authorization.get().substring("Bearer ".length())).getPayload();
        assertThat(claims.get("scope", String.class))
                .isEqualTo("system-admin:organizations:update");
        assertThat(claims.get("command_id", String.class)).isEqualTo(commandId.toString());
    }

    @Test
    void readsBoundedUserResourcesWithExactDedicatedScope() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/system-admin/v1/users/user-1/resources", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getRawQuery());
            write(exchange, mapper.writeValueAsBytes(Map.of(
                    "success", true, "code", "OK", "message", "success",
                    "data", Map.of("items", java.util.List.of(Map.ofEntries(
                            Map.entry("kind", "RUN"), Map.entry("id", "run-1"),
                            Map.entry("state", "FAILED"),
                            Map.entry("createdAt", Instant.now().toString()),
                            Map.entry("updatedAt", Instant.now().toString()),
                            Map.entry("primaryCount", 1), Map.entry("secondaryCount", 0))),
                            "page", 0, "pageSize", 25, "total", 1,
                            "generatedAt", Instant.now().toString()))));
        });
        server.start();
        AdminPlatformClientProperties properties = properties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        AdminPlatformClient client = new AdminPlatformClient(properties,
                new AdminPlatformServiceTokenIssuer(properties, Clock.systemUTC()), mapper);

        var result = client.userResources("user-1", "RUN", 0, 25,
                "00000000-0000-0000-0000-000000000123", "request-1");

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().safeErrorCode()).isNull();
        assertThat(query.get()).contains("kind=RUN", "page=0", "pageSize=25");
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(
                        SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(authorization.get().substring("Bearer ".length())).getPayload();
        assertThat(claims.get("scope", String.class))
                .isEqualTo("system-admin:users:resources:read");
    }

    @Test
    void readsRegistryCandidatesAndApprovesWithDistinctExactScopes() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        AtomicReference<String> readAuthorization = new AtomicReference<>();
        AtomicReference<String> writeAuthorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/system-admin/v1/mcp-registry/candidates", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/approvals")) {
                writeAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                UUID commandId = UUID.fromString(
                        exchange.getRequestHeaders().getFirst("X-Admin-Command-ID"));
                write(exchange, mapper.writeValueAsBytes(Map.of(
                        "success", true, "code", "OK", "message", "success",
                        "data", Map.ofEntries(Map.entry("commandId", commandId.toString()),
                                Map.entry("operation", "MCP_REGISTRY_CANDIDATE_APPROVE"),
                                Map.entry("targetUserId", "candidate-1"),
                                Map.entry("state", "SUCCEEDED"),
                                Map.entry("result", Map.of("reviewState", "APPROVED")),
                                Map.entry("createdAt", Instant.now().toString()),
                                Map.entry("updatedAt", Instant.now().toString())))));
            } else {
                readAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                write(exchange, mapper.writeValueAsBytes(Map.of(
                        "success", true, "code", "OK", "message", "success",
                        "data", Map.of("items", java.util.List.of(), "page", 0,
                                "pageSize", 25, "total", 0,
                                "generatedAt", Instant.now().toString()))));
            }
        });
        server.start();
        AdminPlatformClientProperties properties = properties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        AdminPlatformClient client = new AdminPlatformClient(properties,
                new AdminPlatformServiceTokenIssuer(properties, Clock.systemUTC()), mapper);
        String actor = "00000000-0000-0000-0000-000000000123";

        assertThat(client.mcpRegistryCandidates(
                0, 25, "PENDING_REVIEW", null, actor, "request-read").items()).isEmpty();
        UUID commandId = UUID.randomUUID();
        assertThat(client.approveMcpRegistryCandidate(
                commandId, "approval-key", actor, "request-write", "candidate-1",
                "Reviewed candidate").state()).isEqualTo("SUCCEEDED");

        assertThat(scope(readAuthorization.get())).isEqualTo("system-admin:mcp-registry:read");
        var writeClaims = claims(writeAuthorization.get());
        assertThat(writeClaims.get("scope", String.class))
                .isEqualTo("system-admin:mcp-registry:approve");
        assertThat(writeClaims.get("command_id", String.class)).isEqualTo(commandId.toString());
    }

    private static Map<String, Object> overview() {
        Map<String, Long> identity = Map.ofEntries(
                Map.entry("totalUsers", 2L), Map.entry("pendingUsers", 0L),
                Map.entry("activeUsers", 2L), Map.entry("suspendedUsers", 0L),
                Map.entry("deletionPendingUsers", 0L), Map.entry("deletedUsers", 0L),
                Map.entry("registeredInWindow", 1L),
                Map.entry("uniqueSuccessfulLoginsInWindow", 1L),
                Map.entry("recentlyActiveUsers", 1L), Map.entry("activeRefreshSessions", 1L),
                Map.entry("activeOrganizations", 2L), Map.entry("deletingOrganizations", 0L));
        return Map.ofEntries(
                Map.entry("window", "24h"), Map.entry("generatedAt", Instant.now().toString()),
                Map.entry("releaseVersion", "test"), Map.entry("schemaVersion", 1038),
                Map.entry("platformReadiness", "UP"), Map.entry("identity", identity),
                Map.entry("inference", Map.of("providers", 0, "activeProviders", 0,
                        "unhealthyProviders", 0, "untestedProviders", 0, "disabledProviders", 0,
                        "modelPools", 0, "activeModelPools", 0)),
                Map.entry("agent", Map.of("agents", 0, "activeAgents", 0, "activeApiKeys", 0)),
                Map.entry("project", Map.of("projects", 0, "activeProjects", 0,
                        "workspaces", 0, "activeWorkspaces", 0)),
                Map.entry("conversation", Map.of("conversations", 0,
                        "activeConversations", 0, "messages", 0)),
                Map.entry("runtime", Map.of("runs", 0, "activeRuns", 0, "completedRuns", 0,
                        "failedRuns", 0, "cancelledRuns", 0, "recoveringRuns", 0, "unknownRuns", 0)),
                Map.entry("tooling", Map.of("mcpConnections", 0,
                        "activeMcpConnections", 0, "unknownToolExecutions", 0)));
    }

    private static void write(HttpExchange exchange, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String scope(String authorization) {
        return claims(authorization).get("scope", String.class);
    }

    private static io.jsonwebtoken.Claims claims(String authorization) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(
                        SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(authorization.substring("Bearer ".length())).getPayload();
    }

    private static AdminPlatformClientProperties properties() {
        AdminPlatformClientProperties properties = new AdminPlatformClientProperties();
        properties.setJwtSecret(SECRET);
        properties.setAllowInsecureLocal(true);
        return properties;
    }
}
