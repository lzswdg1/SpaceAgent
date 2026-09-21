package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Current production filters, routes, DTOs and SQL against an isolated database; no paid calls. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class PlatformResourceObservationHttpPostgresTest {
    private static final String SERVICE_SECRET = "resource-observation-local-fixture-secret-0123456789";
    private static final String PUBLIC = "/api/v1/monitoring/resource-observations";
    private static final String INTERNAL = "/internal/system-admin/v1/resource-observations";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> true);
        r.add("spring.flyway.locations", () -> "classpath:db/platform-server,classpath:db/platform-runtime");
        r.add("platform.persistence", () -> "postgres");
        r.add("platform.security.allow-insecure-local", () -> true);
        r.add("platform.system-admin.security.allow-insecure-local", () -> true);
        r.add("platform.system-admin.security.jwt-secret", () -> SERVICE_SECRET);
        r.add("platform.runtime.coordination.enabled", () -> false);
        r.add("platform.inference.health-probes-enabled", () -> false);
        r.add("platform.knowledge.url-refresh.enabled", () -> false);
        r.add("platform.identity.cleanup.worker-enabled", () -> false);
        r.add("platform.identity.user-cleanup.worker-enabled", () -> false);
        r.add("platform.tooling.mcp.registry.worker-enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityApplicationApi identity;

    @Test
    void publicScopeCannotBeOverriddenEvenByAnotherMemberOfTheSameOrganization() throws Exception {
        var owner = register();
        var other = register();
        observations(owner);
        mvc.perform(get(PUBLIC).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.observations").value(2))
                .andExpect(jsonPath("$.data.knownCpuUsageNanos").value(246))
                .andExpect(jsonPath("$.data.latestObservedWorkspaceApparentBytes").value(200))
                .andExpect(jsonPath("$.data.missingNetworkObservations").value(2));
        var foreign = mvc.perform(get(PUBLIC).header("Authorization", bearer(other))
                        .param("organizationId", owner.path("tenantId").asText())
                        .param("userId", owner.path("userId").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.observations").value(0)).andReturn();
        assertThat(json.readTree(foreign.getResponse().getContentAsByteArray()).at("/data/knownCpuUsageNanos").isNull()).isTrue();
        identity.addTenantMembership(new AddTenantMembershipCommand(owner.path("tenantId").asText(),
                other.path("userId").asText(), TenantRole.MEMBER));
        var switched = mvc.perform(post("/api/v1/organizations/" + owner.path("tenantId").asText() + "/switch")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andReturn();
        String token = json.readTree(switched.getResponse().getContentAsByteArray()).at("/data/token").asText();
        mvc.perform(get(PUBLIC).header("Authorization", "Bearer " + token)
                        .param("userId", owner.path("userId").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.observations").value(0));
    }

    @Test
    void administratorNeedsExactDedicatedNonTenantScopeAndCanReadOnlySelectedFacts() throws Exception {
        var owner = register();
        observations(owner);
        mvc.perform(get(INTERNAL)).andExpect(status().isUnauthorized());
        mvc.perform(get(INTERNAL).header("Authorization", bearer(owner))).andExpect(status().isUnauthorized());
        mvc.perform(get(INTERNAL).header("Authorization", serviceToken("system-admin:usage:read", false)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(INTERNAL).header("Authorization", serviceToken("system-admin:resources:observations:read", true)))
                .andExpect(status().isUnauthorized());
        var result = mvc.perform(get(INTERNAL).header("Authorization", serviceToken("system-admin:resources:observations:read", false))
                        .param("organizationId", owner.path("tenantId").asText()).param("userId", owner.path("userId").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.observations").value(2))
                .andExpect(jsonPath("$.data.maximumObservedMemoryBytes").value(456)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("/data/", "command", "environment", "jwt-secret");
    }

    @Test
    void anonymousAndUnboundedQueriesAreRejectedInsteadOfReturningFabricatedTotals() throws Exception {
        var owner = register();
        mvc.perform(get(PUBLIC)).andExpect(status().isUnauthorized());
        mvc.perform(get(PUBLIC).header("Authorization", bearer(owner)).param("from", "not-an-instant"))
                .andExpect(status().isBadRequest());
        for (String from : new String[]{"2026-01-01T00:00:00Z", "2026-09-15T01:00:00Z"}) {
            mvc.perform(get(PUBLIC).header("Authorization", bearer(owner)).param("from", from).param("to", "2026-09-15T00:00:00Z"))
                    .andExpect(status().isBadRequest());
        }
    }

    private JsonNode register() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", UUID.randomUUID() + "@example.com",
                                "password", "Local-Fixture-Password-2026!", "displayName", "Resource fixture"))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).path("data");
    }

    private String bearer(JsonNode user) { return "Bearer " + user.path("token").asText(); }

    private void observations(JsonNode owner) {
        String tenant = owner.path("tenantId").asText(), user = owner.path("userId").asText();
        String agent = UUID.randomUUID().toString(), run = UUID.randomUUID().toString(), workspace = UUID.randomUUID().toString();
        var now = Timestamp.from(Instant.now().minusSeconds(1));
        jdbc.update("INSERT INTO platform_agent_definitions(id,owner_id,tenant_id,name,status,revision,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',1,?,?)",
                agent, user, tenant, agent, now, now);
        jdbc.update("INSERT INTO platform_agent_runs(id,tenant_id,agent_id,owner_id,conversation_id,state,created_at,updated_at) VALUES(?,?,?,?,?,'IN_PROGRESS',?,?)",
                run, tenant, agent, user, UUID.randomUUID().toString(), now, now);
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO platform_run_events(id,agent_run_id,sequence_number,event_type,payload,execution_cursor,created_at) VALUES(?,?,?,'RESOURCE_OBSERVED',?::jsonb,'{}'::jsonb,?)",
                    UUID.randomUUID().toString(), run, i,
                    "{\"executionId\":\"fixture-" + i + "\",\"workspaceId\":\"" + workspace + "\",\"resourceMetrics\":{\"cpuUsageNanos\":123,\"maxObservedMemoryBytes\":456,\"workspaceApparentBytes\":" + i * 100 + "}}",
                    new Timestamp(now.getTime() + i));
        }
    }

    private String serviceToken(String scope, boolean tenantClaim) {
        var now = Instant.now();
        var builder = Jwts.builder().id(UUID.randomUUID().toString()).issuer("spaceagent-platform-admin")
                .audience().add("spaceagent-platform-admin-internal").and().subject("platform-admin-server")
                .claim("actor_id", UUID.randomUUID().toString()).claim("request_id", UUID.randomUUID().toString())
                .claim("scope", scope).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(45)));
        if (tenantClaim) builder.claim("tenant_id", UUID.randomUUID().toString());
        return "Bearer " + builder.signWith(Keys.hmacShaKeyFor(SERVICE_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }
}
