package com.spaceagent.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.admin.command.application.AdminCommandService;
import com.spaceagent.admin.command.domain.AdminCommand;
import com.spaceagent.admin.config.AdminBootstrapProperties;
import com.spaceagent.admin.platformclient.AdminPlatformClient;
import com.spaceagent.admin.platformclient.AdminPlatformClientException;
import com.spaceagent.admin.platformclient.PlatformAdminWire;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static com.spaceagent.admin.identity.http.AdminAuthenticationController.CSRF_HEADER;
import static com.spaceagent.admin.identity.http.AdminAuthenticationController.CSRF_COOKIE;
import static com.spaceagent.admin.identity.http.AdminAuthenticationController.REFRESH_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PlatformAdminAuthenticationPostgresTest {
    private static final String LOGIN = "platform-root";
    private static final String PASSWORD = "Strong-Admin-Password-2026!";
    private static final String JWT_SECRET = "admin-integration-jwt-secret-at-least-32-characters";
    private static final String RECOVERY_CODES =
            "AAAA-BBBB-0001,AAAA-BBBB-0002,AAAA-BBBB-0003,AAAA-BBBB-0004,"
                    + "AAAA-BBBB-0005,AAAA-BBBB-0006,AAAA-BBBB-0007,AAAA-BBBB-0008";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("spaceagent_admin_test")
            .withUsername("spaceagent_admin")
            .withPassword("spaceagent_admin_test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ADMIN_LOGIN", () -> LOGIN);
        registry.add("ADMIN_PASSWORD", () -> PASSWORD);
        registry.add("admin.security.cookie-secure", () -> "false");
        registry.add("admin.security.jwt-secret", () -> JWT_SECRET);
        registry.add("admin.platform-client.allow-insecure-local", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired Clock adminClock;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminCommandService commandService;
    @Autowired AdminBootstrapProperties bootstrapProperties;
    @Autowired com.spaceagent.admin.identity.application.AdminBootstrapRunner bootstrapRunner;
    @MockitoBean AdminPlatformClient platformClient;

    @Test
    void resourceObservationRouteRequiresAdministratorPreservesUnknownsAndPersistsReadAudit() throws Exception {
        var now = Instant.now();
        var expected = new com.spaceagent.admin.platformclient.BusinessEvidenceWire.ResourceObservations(
                2, new java.math.BigDecimal("246"), 456L, null, null, new java.math.BigDecimal("200"),
                0, 0, 2, 0, "PARTIAL", now.minusSeconds(60), now, now);
        when(platformClient.resourceObservations(org.mockito.ArgumentMatchers.anyMap(), anyString(), anyString())).thenReturn(expected);
        mockMvc.perform(get("/admin/v1/resource-observations")).andExpect(status().isUnauthorized());
        String access = json(passwordLogin(LOGIN, PASSWORD)).at("/data/accessToken").asText();
        var response = mockMvc.perform(get("/admin/v1/resource-observations?userId=fixture-user&organizationId=fixture-org")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.observations").value(2))
                .andExpect(jsonPath("$.data.knownCpuUsageNanos").value(246)).andReturn();
        assertThat(json(response).at("/data/knownNetworkRxBytes").isNull()).isTrue();
        mockMvc.perform(get("/admin/v1/resource-observations?from=invalid").header("Authorization", "Bearer " + access))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/v1/resource-observations?from=2026-01-01T00:00:00Z&to=2026-09-15T00:00:00Z")
                        .header("Authorization", "Bearer " + access)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='ADMIN_RESOURCE_OBSERVATIONS_READ'", Long.class))
                .isEqualTo(1L);
        org.mockito.Mockito.verify(platformClient).resourceObservations(eq(Map.of("userId", "fixture-user", "organizationId", "fixture-org")), anyString(), anyString());
    }

    @Test
    void passwordRefreshLogoutAndCommandLifecycleAreDurableAndNonTenant() throws Exception {
        MvcResult verified = passwordLogin(LOGIN, PASSWORD);
        assertThat(json(verified).at("/data/challengeToken").isMissingNode()).isTrue();
        assertThat(verified.getResponse().getCookie(REFRESH_COOKIE).isHttpOnly()).isTrue();
        assertThat(verified.getResponse().getCookie(CSRF_COOKIE).isHttpOnly()).isFalse();
        assertThat(verified.getResponse().getCookie(CSRF_COOKIE).getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_mfa_factors WHERE status='ACTIVE'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_sessions WHERE mfa_verified_at IS NOT NULL", Long.class)).isZero();
        JsonNode verifiedJson = json(verified);
        String accessToken = verifiedJson.at("/data/accessToken").asText();
        UUID administratorId = jdbc.queryForObject(
                "SELECT id FROM admin_principals WHERE login_name = ?", UUID.class, LOGIN);
        String csrf = verifiedJson.at("/data/csrfToken").asText();
        Cookie refreshCookie = verified.getResponse().getCookie(REFRESH_COOKIE);
        Cookie csrfCookie = verified.getResponse().getCookie(CSRF_COOKIE);
        assertThat(refreshCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.getValue()).isEqualTo(csrf);

        mockMvc.perform(post("/admin/v1/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone());

        mockMvc.perform(get("/admin/v1/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginName").value(LOGIN));

        when(platformClient.overview(anyString(), anyString(), anyString()))
                .thenReturn(overview());
        mockMvc.perform(get("/admin/v1/dashboard?window=24h")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(jsonPath("$.data.projection.schemaVersion").value(1038));
        mockMvc.perform(get("/admin/v1/audit-events?page=0&pageSize=25")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].action").value("ADMIN_DASHBOARD_READ"));

        when(platformClient.createUser(any(UUID.class), eq("admin-create-key"), anyString(),
                anyString(), eq("managed@example.com"), eq("Managed User"),
                eq("Managed Organization"), eq(null), eq("Approved support request")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "USER_CREATE", null, Map.of("userId", "managed-user-1",
                                "status", "PENDING_ACTIVATION"), "one-time-activation-token"));
        MvcResult create = mockMvc.perform(post("/admin/v1/users")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginName":"managed@example.com","displayName":"Managed User",\
                                 "organizationName":"Managed Organization",\
                                 "reason":"Approved support request"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.activationToken").value("one-time-activation-token"))
                .andReturn();
        UUID createdCommandId = UUID.fromString(json(create).at("/data/commandId").asText());
        String persistedResult = jdbc.queryForObject(
                "SELECT result_json::text FROM admin_commands WHERE id = ?",
                String.class, createdCommandId);
        assertThat(persistedResult).doesNotContain("one-time-activation-token");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM admin_audit_events
                 WHERE command_id = ? AND reason = 'Approved support request'
                """, Long.class, createdCommandId)).isEqualTo(1L);
        mockMvc.perform(get("/admin/v1/commands?state=SUCCEEDED&pageSize=1000")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(createdCommandId.toString()));

        when(platformClient.presence(anyString(), anyString())).thenReturn(new PlatformAdminWire.PresenceSummary(
                null, null, 0, "NO_CLIENT_HEARTBEATS", "Heartbeat sessions only", 90, Instant.now()));
        when(platformClient.businessAudit(org.mockito.ArgumentMatchers.anyMap(),anyString(),anyString())).thenReturn(new com.spaceagent.admin.platformclient.BusinessEvidenceWire.AuditPage(java.util.List.of(),0,"UNCONFIRMED_VISIBLE"));
        when(platformClient.usageSummary(org.mockito.ArgumentMatchers.anyMap(),anyString(),anyString())).thenReturn(new com.spaceagent.admin.platformclient.BusinessEvidenceWire.UsageOverview(Instant.now().minusSeconds(60),Instant.now(),java.util.List.of(),"NO_RESOURCE_MEASUREMENTS"));
        mockMvc.perform(get("/admin/v1/business-audit").header("Authorization","Bearer "+accessToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.coverage").value("UNCONFIRMED_VISIBLE"));
        mockMvc.perform(get("/admin/v1/usage/summary").header("Authorization","Bearer "+accessToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.coverage").value("NO_RESOURCE_MEASUREMENTS"));
        mockMvc.perform(get("/admin/v1/usage/history?kind=MODEL&pageSize=999").header("Authorization","Bearer "+accessToken)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/v1/usage/summary")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='ADMIN_BUSINESS_AUDIT_READ'",Long.class)).isEqualTo(1L);
        mockMvc.perform(get("/admin/v1/presence").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.coverage").value("NO_CLIENT_HEARTBEATS"));
        when(platformClient.updateUser(any(UUID.class), anyString(), anyString(), anyString(),
                eq("managed-user-1"), eq("Corrected"), anyString()))
                .thenAnswer(i -> successfulCommand(i.getArgument(0), "USER_UPDATE", "managed-user-1",
                        Map.of("userId", "managed-user-1"), null));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/admin/v1/users/managed-user-1")
                .header("Authorization", "Bearer " + accessToken).header("Idempotency-Key", "profile-update-test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Corrected\",\"reason\":\"Support correction\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
        when(platformClient.revokeUserSessions(any(UUID.class), anyString(), anyString(), anyString(),
                eq("managed-user-1"), anyString()))
                .thenAnswer(i -> successfulCommand(i.getArgument(0), "USER_SESSIONS_REVOKE", "managed-user-1",
                        Map.of("sessionsRevoked", true), null));
        mockMvc.perform(post("/admin/v1/users/managed-user-1/session-revocations")
                .header("Authorization", "Bearer " + accessToken).header("Idempotency-Key", "session-revoke-test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Support recovery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
        when(platformClient.resetUserPassword(any(UUID.class), anyString(), anyString(), anyString(),
                eq("managed-user-1"), anyString())).thenAnswer(i -> new PlatformAdminWire.CommandView(
                        i.getArgument(0), "USER_PASSWORD_RESET", "managed-user-1", "SUCCEEDED",
                        Map.of("tokenReplayable", false), null, null, Instant.now(), Instant.now(), Instant.now(),
                        "one-response-reset-secret"));
        String resetBody = "{\"reason\":\"Support recovery\"}";
        mockMvc.perform(post("/admin/v1/users/managed-user-1/password-resets")
                .header("Authorization", "Bearer " + accessToken).header("Idempotency-Key", "password-reset-test")
                .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.passwordResetToken").value("one-response-reset-secret"));
        mockMvc.perform(post("/admin/v1/users/managed-user-1/password-resets")
                .header("Authorization", "Bearer " + accessToken).header("Idempotency-Key", "password-reset-test")
                .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.passwordResetToken").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_commands WHERE result_json::text LIKE '%one-response-reset-secret%'", Long.class)).isZero();

        UUID sessionId = jdbc.queryForObject("""
                SELECT id FROM admin_sessions WHERE principal_id =
                    (SELECT id FROM admin_principals WHERE login_name = ?)
                  AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1
                """, UUID.class, LOGIN);
        jdbc.update("UPDATE admin_sessions SET authenticated_at = clock_timestamp() - interval '6 minutes' WHERE id = ?", sessionId);
        when(platformClient.suspendUser(any(UUID.class), anyString(), anyString(), anyString(),
                eq("managed-user-1"), anyString())).thenAnswer(i -> successfulCommand(i.getArgument(0),
                "USER_SUSPEND", "managed-user-1", Map.of("status", "SUSPENDED"), null));
        mockMvc.perform(post("/admin/v1/users/managed-user-1/suspend")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-suspend-password-session")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Confirmed compromise response\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/v1/auth/mfa/reauthenticate")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isGone());

        when(platformClient.userProviders(eq("managed-user-1"), eq(0), eq(25),
                anyString(), anyString())).thenReturn(new PlatformAdminWire.Page<>(java.util.List.of(
                        new PlatformAdminWire.CredentialItem("PROVIDER", "provider-1", "org-1",
                                "managed-user-1", "provider-1", "Qwen", "https://example.com/v1",
                                "example.com", "BEARER", true, "sk-...1234", "fingerprint",
                                "v1", "ACTIVE", Instant.now(), Map.of())),
                        0, 25, 1, Instant.now()));
        mockMvc.perform(get("/admin/v1/users/managed-user-1/providers")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("Qwen"));

        when(platformClient.userAgents(eq("managed-user-1"), eq(0), eq(25),
                anyString(), anyString())).thenReturn(new PlatformAdminWire.Page<>(java.util.List.of(
                        new PlatformAdminWire.AgentSummary("agent-1", "org-1", "managed-user-1",
                                "Support Agent", "Support workflow", "ACTIVE", 1, 0,
                                Instant.now(), Instant.now(), null)), 0, 25, 1, Instant.now()));
        mockMvc.perform(get("/admin/v1/users/managed-user-1/agents")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("Support Agent"));

        when(platformClient.userResourceOverview(eq("managed-user-1"), anyString(), anyString()))
                .thenReturn(new PlatformAdminWire.UserResourceOverview(
                        1, 1, 2, 1, 3, 1, 2, 4, 1, 5, 1, 2));
        mockMvc.perform(get("/admin/v1/users/managed-user-1/resource-overview")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelPools").value(1))
                .andExpect(jsonPath("$.data.runs").value(5))
                .andExpect(jsonPath("$.data.toolEffects").value(2));

        when(platformClient.userResources(eq("managed-user-1"), eq("RUN"), eq(0), eq(25),
                anyString(), anyString())).thenReturn(new PlatformAdminWire.Page<>(java.util.List.of(
                        new PlatformAdminWire.UserResourceSummary(
                                "RUN", "run-1", "org-1", "project-1", null, "FAILED",
                                "agent-1", Instant.now(), Instant.now(), "SAFE_RUN_FAILURE", 3, 1)),
                        0, 25, 1, Instant.now()));
        mockMvc.perform(get("/admin/v1/users/managed-user-1/resources?kind=RUN")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].kind").value("RUN"))
                .andExpect(jsonPath("$.data.items[0].safeErrorCode").value("SAFE_RUN_FAILURE"));
        mockMvc.perform(get("/admin/v1/users/managed-user-1/resources?kind=SQL_CONSOLE")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADMIN_USER_RESOURCE_KIND_INVALID"));

        when(platformClient.createOrganization(any(UUID.class), eq("admin-org-create"),
                anyString(), anyString(), eq("managed-user-1"), eq("Managed Team"),
                eq("managed-team"), eq("Approved team provisioning")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "ORGANIZATION_CREATE", null, Map.of("organizationId", "organization-1",
                                "status", "ACTIVE"), null));
        mockMvc.perform(post("/admin/v1/organizations")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-org-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerUserId":"managed-user-1","name":"Managed Team",\
                                 "slug":"managed-team","reason":"Approved team provisioning"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("ORGANIZATION_CREATE"))
                .andExpect(jsonPath("$.data.result.organizationId").value("organization-1"));

        when(platformClient.updateOrganization(any(UUID.class), eq("admin-org-update"),
                anyString(), anyString(), eq("organization-1"), eq("Managed Team 2"),
                eq("managed-team-2"), eq("Approved team rename")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "ORGANIZATION_UPDATE", "organization-1",
                        Map.of("organizationId", "organization-1", "status", "ACTIVE"), null));
        mockMvc.perform(patch("/admin/v1/organizations/organization-1")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-org-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Managed Team 2","slug":"managed-team-2",\
                                 "reason":"Approved team rename"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("ORGANIZATION_UPDATE"));

        when(platformClient.organizationMembers(eq("organization-1"), eq(0), eq(25),
                eq(null), eq(null), eq(null), anyString(), anyString()))
                .thenReturn(new PlatformAdminWire.Page<>(java.util.List.of(
                        new PlatformAdminWire.OrganizationMemberSummary("organization-1",
                                "managed-user-1", "managed@example.com", "Managed User",
                                "ACTIVE", "OWNER", "ACTIVE", Instant.now(), Instant.now())),
                        0, 25, 1, Instant.now()));
        mockMvc.perform(get("/admin/v1/organizations/organization-1/members")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].role").value("OWNER"));

        when(platformClient.addOrganizationMember(any(UUID.class), eq("admin-member-add"),
                anyString(), anyString(), eq("organization-1"), eq("member-2"), eq("MEMBER"),
                eq("Approved membership")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "ORGANIZATION_MEMBER_ADD", "organization-1",
                        Map.of("userId", "member-2", "role", "MEMBER", "status", "ACTIVE"), null));
        mockMvc.perform(post("/admin/v1/organizations/organization-1/members")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-member-add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"member-2","role":"MEMBER",\
                                 "reason":"Approved membership"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.role").value("MEMBER"));

        when(platformClient.updateOrganizationMemberRole(any(UUID.class), eq("admin-member-role"),
                anyString(), anyString(), eq("organization-1"), eq("member-2"), eq("ADMIN"),
                eq("Approved role change")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "ORGANIZATION_MEMBER_ROLE_UPDATE", "organization-1",
                        Map.of("userId", "member-2", "role", "ADMIN", "status", "ACTIVE"), null));
        mockMvc.perform(patch("/admin/v1/organizations/organization-1/members/member-2")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-member-role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"reason\":\"Approved role change\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.role").value("ADMIN"));

        when(platformClient.transferOrganizationOwnership(any(UUID.class), eq("admin-owner-transfer"),
                anyString(), anyString(), eq("organization-1"), eq("member-2"),
                eq("Approved ownership transfer")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "ORGANIZATION_OWNER_TRANSFER", "organization-1",
                        Map.of("organizationId", "organization-1", "creatorUserId", "member-2"), null));
        mockMvc.perform(post("/admin/v1/organizations/organization-1/ownership-transfers")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-owner-transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newOwnerUserId":"member-2",\
                                 "reason":"Approved ownership transfer"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.creatorUserId").value("member-2"));

        when(platformClient.removeOrganizationMember(any(UUID.class), eq("admin-member-remove"),
                anyString(), anyString(), eq("organization-1"), eq("managed-user-1"),
                eq("Approved member removal")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "ORGANIZATION_MEMBER_REMOVE", "organization-1",
                        Map.of("userId", "managed-user-1", "status", "SUSPENDED"), null));
        mockMvc.perform(delete("/admin/v1/organizations/organization-1/members/managed-user-1")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-member-remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Approved member removal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("SUSPENDED"));

        when(platformClient.deleteOrganization(any(UUID.class), eq("admin-org-delete"),
                anyString(), anyString(), eq("organization-1"),
                eq("Approved team decommission")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "ORGANIZATION_DELETE", "organization-1",
                        Map.of("organizationId", "organization-1", "status", "DELETING"), null));
        mockMvc.perform(post("/admin/v1/organizations/organization-1/deletion-jobs")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-org-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Approved team decommission\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("DELETING"));

        when(platformClient.suspendUser(any(UUID.class), eq("admin-suspend-key"), anyString(),
                anyString(), eq("managed-user-1"), eq("Confirmed compromise response")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "USER_SUSPEND", "managed-user-1",
                        Map.of("userId", "managed-user-1", "status", "SUSPENDED"), null));
        mockMvc.perform(post("/admin/v1/users/managed-user-1/suspend")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-suspend-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Confirmed compromise response\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"));

        when(platformClient.requestDeletion(any(UUID.class), eq("admin-erase-key"), anyString(),
                anyString(), eq("managed-user-1"), eq("Approved durable erasure")))
                .thenAnswer(invocation -> successfulCommand(invocation.getArgument(0),
                        "USER_DELETION_REQUEST", "managed-user-1",
                        Map.of("userId", "managed-user-1", "cleanupState", "PENDING",
                                "physicalDeletionEnabled", true), null));
        mockMvc.perform(post("/admin/v1/users/managed-user-1/deletion-jobs")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-erase-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Approved durable erasure\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("USER_DELETION_REQUEST"))
                .andExpect(jsonPath("$.data.result.physicalDeletionEnabled").value(true));

        when(platformClient.userCleanupJob(eq("managed-user-1"), anyString(), anyString()))
                .thenReturn(cleanupJob("managed-user-1"));
        mockMvc.perform(get("/admin/v1/users/managed-user-1/deletion-jobs/current")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.state").value("PENDING"))
                .andExpect(jsonPath("$.data.steps[0].stepKey").value("AUTH_FREEZE"));

        when(platformClient.cleanupJobs(eq(0), eq(25), eq("USER"), eq("BLOCKED"),
                eq(null), anyString(), anyString())).thenReturn(new PlatformAdminWire.Page<>(
                        java.util.List.of(cleanupSummary("managed-user-1", "BLOCKED")),
                        0, 25, 1, Instant.now()));
        mockMvc.perform(get("/admin/v1/cleanup-jobs?kind=USER&state=BLOCKED")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lastErrorCode")
                        .value("UNKNOWN_TOOL_EFFECT_BLOCKER"));
        when(platformClient.cleanupOverview(anyString(), anyString()))
                .thenReturn(new PlatformAdminWire.CleanupOverview(0, 0, 0, 1, 0,
                        java.util.List.of(new PlatformAdminWire.BlockerCount(
                                "UNKNOWN_TOOL_EFFECT_BLOCKER", 1))));
        mockMvc.perform(get("/admin/v1/cleanup-jobs/overview")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blocked").value(1));
        when(platformClient.cleanupJob(eq("USER"), eq("managed-user-1"), anyString(), anyString()))
                .thenReturn(cleanupDetail("managed-user-1"));
        mockMvc.perform(get("/admin/v1/cleanup-jobs/USER/managed-user-1")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].stepKey").value("AUTH_FREEZE"));

        when(platformClient.deletionPreflight(any(UUID.class), eq("admin-delete-key"),
                anyString(), anyString(), eq("managed-user-1"),
                eq("Approved deletion investigation")))
                .thenThrow(new AdminPlatformClientException(
                        "PLATFORM_COMMAND_UNKNOWN", "Timed out after dispatch"));
        MvcResult unknown = mockMvc.perform(post("/admin/v1/users/managed-user-1/deletion-requests")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "admin-delete-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Approved deletion investigation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("UNKNOWN"))
                .andReturn();
        UUID unknownCommandId = UUID.fromString(json(unknown).at("/data/commandId").asText());
        when(platformClient.command(eq(unknownCommandId), anyString(), anyString()))
                .thenReturn(successfulCommand(unknownCommandId, "USER_DELETION_PREFLIGHT",
                        "managed-user-1", Map.of("eligible", false,
                                "physicalDeletionEnabled", false), null));
        mockMvc.perform(post("/admin/v1/commands/{commandId}/reconcile", unknownCommandId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.physicalDeletionEnabled").value(false));

        mockMvc.perform(post("/admin/v1/administrators")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "singleton-create-denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginName":"secondary-admin","displayName":"Secondary Admin",\
                                 "reason":"Verify singleton enforcement"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_SINGLETON_PRINCIPAL_ENFORCED"));

        mockMvc.perform(get("/admin/v1/administrators?pageSize=1000")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(administratorId.toString()));

        mockMvc.perform(get("/admin/v1/administrators/{principalId}/sessions", UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_SINGLETON_PRINCIPAL_ENFORCED"));

        mockMvc.perform(post(
                        "/admin/v1/administrators/{principalId}/sessions/{sessionId}/revocations",
                        administratorId, sessionId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "revoke-current-session-denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Verify current session protection\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_CURRENT_SESSION_PROTECTED"));

        mockMvc.perform(post("/admin/v1/administrators/{principalId}/suspensions", administratorId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", "singleton-suspend-denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Verify singleton enforcement\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_SINGLETON_PRINCIPAL_ENFORCED"));

        for (String retired : java.util.List.of("recovery-codes/rotation", "password", "mfa/recovery")) {
            mockMvc.perform(post("/admin/v1/auth/" + retired).header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isGone());
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_principals", Long.class)).isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO admin_principals(
                    id,login_name,display_name,status,admin_role,credential_version,mfa_required,
                    must_change_password,last_successful_login_at,created_at,updated_at,singleton_slot)
                VALUES (?,'second','Second','ACTIVE','PLATFORM_SUPER_ADMIN',1,TRUE,FALSE,NULL,
                    clock_timestamp(),clock_timestamp(),1)
                """, UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(5L);
        String tenantPlaneToken = Jwts.builder()
                .issuer("spaceagent-platform-server")
                .audience().add("spaceagent-platform-api").and()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(adminClock.instant()))
                .expiration(Date.from(adminClock.instant().plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
        mockMvc.perform(get("/admin/v1/me")
                        .header("Authorization", "Bearer " + tenantPlaneToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/admin/v1/auth/refresh")
                        .cookie(refreshCookie)
                        .header(CSRF_HEADER, csrf))
                .andExpect(status().isUnauthorized());

        MvcResult refreshed = mockMvc.perform(post("/admin/v1/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header(CSRF_HEADER, csrf))
                .andExpect(status().isOk())
                .andReturn();
        String nextCsrf = json(refreshed).at("/data/csrfToken").asText();
        Cookie nextRefreshCookie = refreshed.getResponse().getCookie(REFRESH_COOKIE);
        Cookie nextCsrfCookie = refreshed.getResponse().getCookie(CSRF_COOKIE);
        assertThat(nextRefreshCookie).isNotNull();
        assertThat(nextCsrfCookie).isNotNull();
        assertThat(nextRefreshCookie.getValue()).isNotEqualTo(refreshCookie.getValue());
        assertThat(nextCsrfCookie.getValue()).isEqualTo(nextCsrf).isNotEqualTo(csrf);

        mockMvc.perform(post("/admin/v1/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header(CSRF_HEADER, csrf))
                .andExpect(status().isUnauthorized());

        AdminCommand first = commandService.receive(administratorId, "USER_DISABLE",
                "PLATFORM_USER", "user-1", "stable-key", "a".repeat(64), "request-1");
        AdminCommand replay = commandService.receive(administratorId, "USER_DISABLE",
                "PLATFORM_USER", "user-1", "stable-key", "a".repeat(64), "request-2");
        assertThat(replay.id()).isEqualTo(first.id());

        mockMvc.perform(post("/admin/v1/auth/logout")
                        .cookie(nextRefreshCookie, nextCsrfCookie)
                        .header(CSRF_HEADER, nextCsrf))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge(REFRESH_COOKIE, 0))
                .andExpect(cookie().maxAge(CSRF_COOKIE, 0));

        String rotatedAccessToken = json(refreshed).at("/data/accessToken").asText();
        mockMvc.perform(get("/admin/v1/me")
                        .header("Authorization", "Bearer " + rotatedAccessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_INVALID_SESSION"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_events", Long.class))
                .isGreaterThanOrEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.platform_users')", String.class))
                .isNull();

        // Same configuration is idempotent; changed configuration invalidates old sessions.
        var live = passwordLogin(LOGIN, PASSWORD);
        String liveToken = json(live).at("/data/accessToken").asText();
        Long version = jdbc.queryForObject("SELECT credential_version FROM admin_principals", Long.class);
        bootstrapRunner.run(null);
        assertThat(jdbc.queryForObject("SELECT credential_version FROM admin_principals", Long.class)).isEqualTo(version);
        mockMvc.perform(get("/admin/v1/me").header("Authorization", "Bearer " + liveToken)).andExpect(status().isOk());
        try {
            bootstrapProperties.setPassword("Reconfigured-Password-2026!");
            bootstrapProperties.setLoginName("renamed-root");
            bootstrapRunner.run(null);
            assertThat(jdbc.queryForObject("SELECT id FROM admin_principals", UUID.class)).isEqualTo(administratorId);
            assertThat(jdbc.queryForObject("SELECT credential_version FROM admin_principals", Long.class)).isEqualTo(version + 1);
            mockMvc.perform(get("/admin/v1/me").header("Authorization", "Bearer " + liveToken)).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/admin/v1/auth/refresh")
                    .cookie(live.getResponse().getCookie(REFRESH_COOKIE), live.getResponse().getCookie(CSRF_COOKIE))
                    .header(CSRF_HEADER, json(live).at("/data/csrfToken").asText())).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/admin/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("loginName",LOGIN,"password",PASSWORD))))
                    .andExpect(status().isUnauthorized());
            passwordLogin("renamed-root", "Reconfigured-Password-2026!");
            bootstrapProperties.setPassword("");
            assertThatThrownBy(() -> bootstrapRunner.run(null)).isInstanceOf(IllegalStateException.class);
        } finally {
            bootstrapProperties.setLoginName(LOGIN);
            bootstrapProperties.setPassword(PASSWORD);
            bootstrapRunner.run(null);
        }
        assertThat(jdbc.queryForObject("SELECT password_hash FROM admin_credentials", String.class)).doesNotContain(PASSWORD);
        assertV4RejectsMultiPrincipalUpgrade();
    }

    @Test
    void upgradeV4PreservesIdentityAndHistoricalMfaButRevokesOldSessions() {
        String schema = "admin_v4_password_upgrade";
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/platform-admin-server").schemas(schema).defaultSchema(schema);
        config.target(MigrationVersion.fromVersion("4")).load().migrate();
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO admin_v4_password_upgrade.admin_principals
                  (id,login_name,display_name,status,admin_role,credential_version,mfa_required,
                   must_change_password,created_at,updated_at)
                VALUES (?,'historical','Historical','ACTIVE','PLATFORM_SUPER_ADMIN',1,TRUE,FALSE,now(),now())
                """, id);
        jdbc.update("""
                INSERT INTO admin_v4_password_upgrade.admin_sessions
                  (id,principal_id,refresh_token_hash,csrf_token_hash,credential_version,
                   mfa_verified_at,expires_at,created_at,last_rotated_at)
                VALUES (?,?,?, ?,1,now(),now()+interval '1 day',now(),now())
                """, session, id, "a".repeat(64), "b".repeat(64));
        jdbc.update("""
                INSERT INTO admin_v4_password_upgrade.admin_mfa_factors
                  (id,principal_id,factor_type,secret_ciphertext,status,verified_at,created_at,updated_at)
                VALUES (?,?,'TOTP','historical-ciphertext','ACTIVE',now(),now(),now())
                """, UUID.randomUUID(), id);
        config.target(MigrationVersion.LATEST).load().migrate();
        assertThat(jdbc.queryForObject("SELECT id FROM admin_v4_password_upgrade.admin_principals", UUID.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT credential_version FROM admin_v4_password_upgrade.admin_principals", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT mfa_required FROM admin_v4_password_upgrade.admin_principals", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL AND authenticated_at=mfa_verified_at FROM admin_v4_password_upgrade.admin_sessions", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM admin_v4_password_upgrade.admin_mfa_factors", String.class)).isEqualTo("REVOKED");
    }

    private MvcResult passwordLogin(String login, String password) throws Exception {
        return mockMvc.perform(post("/admin/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("loginName",login,"password",password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isNotEmpty()).andReturn();
    }

    private void assertV4RejectsMultiPrincipalUpgrade() throws Exception {
        String schema = "admin_v3_multiple_principals";
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/platform-admin-server")
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("3"))
                .load()
                .migrate();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO admin_v3_multiple_principals.admin_principals(
                         id,login_name,display_name,status,admin_role,credential_version,
                         mfa_required,last_successful_login_at,created_at,updated_at,
                         must_change_password)
                     VALUES (?,'first-admin','First','ACTIVE','PLATFORM_SUPER_ADMIN',1,TRUE,NULL,
                         clock_timestamp(),clock_timestamp(),FALSE),
                            (?,'second-admin','Second','ACTIVE','PLATFORM_SUPER_ADMIN',1,TRUE,NULL,
                         clock_timestamp(),clock_timestamp(),FALSE)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, UUID.randomUUID());
            statement.executeUpdate();
        }
        assertThatThrownBy(() -> Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/platform-admin-server")
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate())
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                .hasMessageContaining("Admin V4 requires exactly one or zero");
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static PlatformAdminWire.Overview overview() {
        return new PlatformAdminWire.Overview("24h", java.time.Instant.now(), "test", 1038, "UP",
                new PlatformAdminWire.IdentityOverview(1, 0, 1, 0, 0, 0, 1, 1, 1, 1, 1, 0),
                new PlatformAdminWire.InferenceOverview(0, 0, 0, 0, 0, 0, 0),
                new PlatformAdminWire.AgentOverview(0, 0, 0),
                new PlatformAdminWire.ProjectOverview(0, 0, 0, 0),
                new PlatformAdminWire.ConversationOverview(0, 0, 0),
                new PlatformAdminWire.RuntimeOverview(0, 0, 0, 0, 0, 0, 0),
                new PlatformAdminWire.ToolingOverview(0, 0, 0));
    }

    private static PlatformAdminWire.CommandView successfulCommand(
            UUID commandId, String operation, String targetUserId, Map<String, Object> result,
            String activationToken) {
        Instant now = Instant.now();
        return new PlatformAdminWire.CommandView(commandId, operation, targetUserId, "SUCCEEDED",
                result, null, activationToken, now, now, now);
    }

    private static PlatformAdminWire.UserCleanupJobProjection cleanupJob(String userId) {
        Instant now = Instant.now();
        return new PlatformAdminWire.UserCleanupJobProjection(
                new PlatformAdminWire.UserCleanupJob(userId, UUID.randomUUID(), UUID.randomUUID(),
                        "PENDING", now, now, 0, 10, null, null, 0, null,
                        null, null, 0, now, now, null),
                java.util.List.of(new PlatformAdminWire.UserCleanupStep(userId, "AUTH_FREEZE", 100,
                        "PENDING", 0, null, null, now, now, null)));
    }

    private static PlatformAdminWire.CleanupJobSummary cleanupSummary(String userId, String state) {
        Instant now = Instant.now();
        return new PlatformAdminWire.CleanupJobSummary("USER", userId, state,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), now, now, 1, 10,
                "UNKNOWN_TOOL_EFFECT_BLOCKER", "Unknown Tool effect requires reconciliation",
                2, 4, 15, "TOOLING_PRIVATE_RESOURCE_PURGE", now, now, null);
    }

    private static PlatformAdminWire.CleanupJobDetail cleanupDetail(String userId) {
        Instant now = Instant.now();
        return new PlatformAdminWire.CleanupJobDetail(cleanupSummary(userId, "BLOCKED"),
                java.util.List.of(new PlatformAdminWire.CleanupStepSummary("AUTH_FREEZE", 100,
                        "COMPLETED", 1, null, null, now, now, now)));
    }
}
