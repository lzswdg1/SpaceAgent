package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.spaceagent.platform.integration.application.OrganizationCleanupExecutionCoordinator;
import com.spaceagent.platform.integration.application.UserCleanupExecutionCoordinator;
import com.spaceagent.platform.identity.api.UserCleanupApplicationApi;
import com.spaceagent.platform.identity.api.IdentitySystemAdministrationApi;
import com.spaceagent.platform.identity.domain.UserCleanupStepKey;
import com.spaceagent.platform.tooling.api.McpRegistryAdministrationApi;
import com.spaceagent.platform.tooling.domain.McpRegistryCompatibility;
import com.spaceagent.platform.tooling.domain.McpRegistryGateway;
import com.spaceagent.platform.tooling.domain.McpRegistrySnapshot;
import com.spaceagent.platform.tooling.domain.McpRegistryStatus;
import com.spaceagent.shared.exception.BusinessException;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class PlatformSystemAdministrationPostgresTest {
    private static final String SYSTEM_SECRET = "system-admin-integration-secret-0123456789-abcdef";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("spaceagent_system_admin")
            .withUsername("spaceagent")
            .withPassword("spaceagent-test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations",
                () -> "classpath:db/platform-server,classpath:db/platform-runtime");
        registry.add("platform.persistence", () -> "postgres");
        registry.add("platform.runtime.coordination.enabled", () -> "false");
        registry.add("platform.security.allow-insecure-local", () -> "true");
        registry.add("platform.system-admin.security.allow-insecure-local", () -> "true");
        registry.add("platform.system-admin.security.jwt-secret", () -> SYSTEM_SECRET);
        registry.add("platform.identity.activity-hash-key",
                () -> "identity-activity-integration-key-0123456789-abcdef");
        registry.add("platform.identity.cleanup.worker-enabled", () -> "false");
        registry.add("platform.identity.cleanup.retention-hours", () -> "0");
        registry.add("platform.identity.user-cleanup.worker-enabled", () -> "false");
        registry.add("platform.identity.user-cleanup.retention-hours", () -> "0");
        registry.add("platform.identity.user-cleanup.deletion-enabled", () -> "true");
        registry.add("platform.tooling.mcp.registry.worker-enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserCleanupExecutionCoordinator userCleanup;
    @Autowired OrganizationCleanupExecutionCoordinator organizationCleanup;
    @Autowired UserCleanupApplicationApi userCleanupControl;
    @Autowired IdentitySystemAdministrationApi identityAdministration;
    @Autowired McpRegistryAdministrationApi mcpRegistry;
    @MockitoBean McpRegistryGateway mcpRegistryGateway;

    @Test
    void exactScopeBoundaryServesBoundedRedactedOwnerProjections() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin-projection@example.com",\
                                 "password":"ProjectionPassword123!",\
                                 "displayName":"Projection User"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode auth = json(registration).path("data");
        String accessToken = auth.path("token").asText();
        String userId = auth.path("userId").asText();
        String tenantId = auth.path("tenantId").asText();

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("User-Agent", "Mozilla/5.0 test"))
                .andExpect(status().isOk());

        jdbc.update("""
                INSERT INTO platform_model_providers (
                    id, tenant_id, owner_id, name, provider_type, base_url,
                    api_key_ciphertext, auth_type, enabled, is_default,
                    created_at, updated_at, connection_status, last_tested_at,
                    last_test_latency_ms, last_test_error_code
                ) VALUES (?, ?, ?, 'Qwen', 'openai-compatible',
                    'https://dashscope.aliyuncs.com/compatible-mode/v1',
                    'v2:key-id:nonce:TOP_SECRET_CIPHERTEXT', 'BEARER', TRUE, TRUE,
                    clock_timestamp(), clock_timestamp(), 'ACTIVE', clock_timestamp(), 21, NULL)
                """, UUID.randomUUID().toString(), tenantId, userId);
        String agentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_agent_definitions (
                    id, tenant_id, owner_id, name, description, status, revision,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 'Support Agent', 'Owned agent detail', 'ACTIVE', 1,
                    clock_timestamp(), clock_timestamp())
                """, agentId, tenantId, userId);

        mockMvc.perform(get("/internal/system-admin/v1/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SYSTEM_ADMIN_TOKEN_REQUIRED"));
        mockMvc.perform(get("/internal/system-admin/v1/overview")
                        .header("X-Internal-Token", "any-generic-internal-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/system-admin/v1/overview")
                        .header("Authorization", "Bearer " + token("system-admin:users:read", false)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/system-admin/v1/overview")
                        .header("Authorization", "Bearer " + token("system-admin:overview:read", true)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/internal/system-admin/v1/overview")
                        .header("Authorization", "Bearer " + token("system-admin:overview:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaVersion").value(1098))
                .andExpect(jsonPath("$.data.identity.totalUsers").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.identity.uniqueSuccessfulLoginsInWindow")
                        .value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.identity.recentlyActiveUsers")
                        .value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.inference.providers").value(1));

        mockMvc.perform(get("/internal/system-admin/v1/users?page=0&pageSize=1000")
                        .header("Authorization", "Bearer " + token("system-admin:users:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].lastLoginAt").exists())
                .andExpect(jsonPath("$.data.items[0].lastSeenAt").exists());

        MvcResult credentials = mockMvc.perform(get(
                        "/internal/system-admin/v1/credential-inventory?kind=provider")
                        .header("Authorization", "Bearer " + token(
                                "system-admin:credentials:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].secretConfigured").value(true))
                .andExpect(jsonPath("$.data.items[0].baseUrl").value(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .andReturn();
        String credentialBody = credentials.getResponse().getContentAsString();
        assertThat(credentialBody).doesNotContain("TOP_SECRET_CIPHERTEXT", "api_key_ciphertext");

        mockMvc.perform(get("/internal/system-admin/v1/users/{userId}/providers", userId)
                        .header("Authorization", "Bearer " + token(
                                "system-admin:users:providers:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].ownerUserId").value(userId))
                .andExpect(jsonPath("$.data.items[0].name").value("Qwen"));
        mockMvc.perform(get("/internal/system-admin/v1/users/{userId}/agents", userId)
                        .header("Authorization", "Bearer " + token(
                                "system-admin:users:agents:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(agentId))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].activeKeyCount").value(0));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(110L);
    }

    @Test
    void userResourceTopologyIsOwnerScopedPaginatedAndContentRedacted() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "resource-owner-" + UUID.randomUUID() + "@example.com",
                                "password", "ResourceOwnerPassword123!",
                                "displayName", "Resource Owner"))))
                .andExpect(status().isOk()).andReturn();
        String userId = json(registration).at("/data/userId").asText();
        String tenantId = json(registration).at("/data/tenantId").asText();
        String poolId = UUID.randomUUID().toString();
        String projectId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        String agentId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        String stepId = UUID.randomUUID().toString();
        String installationId = UUID.randomUUID().toString();

        jdbc.update("""
                INSERT INTO platform_model_pools(
                    id,tenant_id,owner_id,name,visibility,routing_strategy,fallback_enabled,status,
                    created_at,updated_at)
                VALUES (CAST(? AS UUID),?,?,'Owner Pool','PRIVATE','PRIORITY',TRUE,'ACTIVE',
                    clock_timestamp(),clock_timestamp())
                """, poolId, tenantId, userId);
        jdbc.update("""
                INSERT INTO platform_projects(id,tenant_id,owner_id,name,status,created_at,updated_at)
                VALUES (CAST(? AS UUID),?,?,'Owner Project','ACTIVE',clock_timestamp(),clock_timestamp())
                """, projectId, tenantId, userId);
        jdbc.update("""
                INSERT INTO platform_project_memberships(id,project_id,user_id,role,created_at)
                VALUES (CAST(? AS UUID),CAST(? AS UUID),?,'OWNER',clock_timestamp())
                """, UUID.randomUUID(), projectId, userId);
        jdbc.update("""
                INSERT INTO platform_tasks(
                    id,project_id,tenant_id,owner_user_id,title,goal,state,created_at,updated_at)
                VALUES (CAST(? AS UUID),CAST(? AS UUID),?,?,'Task metadata','private task goal',
                    'FAILED',clock_timestamp(),clock_timestamp())
                """, taskId, projectId, tenantId, userId);
        jdbc.update("""
                INSERT INTO platform_conversations(
                    id,tenant_id,user_id,title,status,created_at,updated_at)
                VALUES (?,?,?,'private conversation title','ACTIVE',clock_timestamp(),clock_timestamp())
                """, conversationId, tenantId, userId);
        jdbc.update("""
                INSERT INTO platform_knowledge_documents(
                    id,owner_id,name,content_type,storage_location,status,created_at,updated_at)
                VALUES (?,?, 'private-file-name.md','text/markdown','inline:PRIVATE_DOCUMENT_BODY',
                    'READY',clock_timestamp(),clock_timestamp())
                """, UUID.randomUUID().toString(), userId);
        jdbc.update("""
                INSERT INTO platform_memory_candidates(
                    id,scope_type,scope_id,kind,source_id,source_type,content,confidence,dedupe_key,
                    state,created_at)
                VALUES (?,'USER',?,'PREFERENCE','message-private','MESSAGE',
                    'PRIVATE_MEMORY_CONTENT',0.9,'private-dedupe','PROPOSED',clock_timestamp())
                """, UUID.randomUUID().toString(), userId);
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,tenant_id,owner_id,name,status,revision,created_at,updated_at)
                VALUES (?,?,?,'Resource Agent','ACTIVE',1,clock_timestamp(),clock_timestamp())
                """, agentId, tenantId, userId);
        jdbc.update("""
                INSERT INTO platform_agent_runs(
                    id,agent_id,tenant_id,owner_id,conversation_id,state,failure_reason,
                    created_at,updated_at,completed_at)
                VALUES (?,?,?,?,?,'FAILED','SAFE_RUN_FAILURE',clock_timestamp(),clock_timestamp(),
                    clock_timestamp())
                """, runId, agentId, tenantId, userId, conversationId);
        jdbc.update("""
                INSERT INTO platform_run_steps(id,agent_run_id,sequence,type,state,created_at,completed_at)
                VALUES (?,?,0,'inference','FAILED',clock_timestamp(),clock_timestamp())
                """, stepId, runId);
        jdbc.update("""
                INSERT INTO platform_model_call_ledger(
                    id,agent_run_id,run_step_id,logical_call_id,request_hash,status,provider_id,
                    model_id,error_code,error_summary,created_at,updated_at)
                VALUES (?,?,?,'resource-call',?,'UNKNOWN','provider-safe','model-safe',
                    'MODEL_RESPONSE_UNKNOWN','PRIVATE_MODEL_ERROR_SUMMARY',clock_timestamp(),clock_timestamp())
                """, UUID.randomUUID().toString(), runId, stepId, "a".repeat(64));
        mockMvc.perform(get("/internal/system-admin/v1/overview")
                        .header("Authorization", "Bearer " + token("system-admin:overview:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inference.unknownModelCalls").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.runtime.unknownRuns").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.platformReadiness").value("NOT_CHECKED"));
        jdbc.update("""
                INSERT INTO platform_tool_execution_ledger(
                    id,agent_run_id,run_step_id,tool_name,tool_call_id,arguments,input_hash,status,
                    error,started_at,completed_at,revision,updated_at)
                VALUES (?,?,?,'web_search','resource-tool','PRIVATE_TOOL_ARGUMENTS',?,
                    'UNKNOWN','TOOL_EFFECT_UNKNOWN',clock_timestamp(),clock_timestamp(),1,
                    clock_timestamp())
                """, UUID.randomUUID().toString(), runId, stepId, "sha256:" + "b".repeat(64));
        jdbc.update("""
                INSERT INTO platform_mcp_installations(
                    id,entry_id,server_version_id,tenant_id,subject_id,created_by,scope,display_name,state,
                    created_at,updated_at)
                VALUES (CAST(? AS UUID),'26000000-0000-4000-8000-000000000002',
                    '10390000-0000-4000-8000-000000000102',?,?,?,'USER',
                    'Owner MCP','INSTALLED',clock_timestamp(),clock_timestamp())
                """, installationId, tenantId, userId, userId);
        jdbc.update("""
                INSERT INTO platform_mcp_connections(
                    id,installation_id,tenant_id,managed_by,endpoint_url,encrypted_auth_json,
                    auth_type,state,revision,created_at,updated_at)
                VALUES (CAST(? AS UUID),CAST(? AS UUID),?,?,'https://mcp.example.com/api',
                    'PRIVATE_MCP_CIPHERTEXT','CUSTOM','ACTIVE',1,clock_timestamp(),clock_timestamp())
                """, UUID.randomUUID(), installationId, tenantId, userId);
        jdbc.update("""
                INSERT INTO platform_automation_schedules(
                    id,tenant_id,owner_id,agent_id,description,prompt,schedule_type,scheduled_at,
                    timezone,state,next_fire_at,last_error,run_count,max_retries,revision,
                    created_at,updated_at)
                VALUES (CAST(? AS UUID),?,?,?,'private automation description',
                    'PRIVATE_AUTOMATION_PROMPT','ONE_TIME',clock_timestamp(),'UTC','FAILED',NULL,
                    'private automation failure',0,1,1,clock_timestamp(),clock_timestamp())
                """, UUID.randomUUID(), tenantId, userId, agentId);

        String scope = "system-admin:users:resources:read";
        MvcResult overview = mockMvc.perform(get(
                        "/internal/system-admin/v1/users/{userId}/resource-overview", userId)
                        .header("Authorization", "Bearer " + token(scope, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelPools").value(1))
                .andExpect(jsonPath("$.data.projects").value(1))
                .andExpect(jsonPath("$.data.tasks").value(1))
                .andExpect(jsonPath("$.data.workspaces").value(0))
                .andExpect(jsonPath("$.data.conversations").value(1))
                .andExpect(jsonPath("$.data.modelEffects").value(1))
                .andExpect(jsonPath("$.data.toolEffects").value(1))
                .andReturn();

        StringBuilder evidence = new StringBuilder(overview.getResponse().getContentAsString());
        for (String kind : com.spaceagent.platform.integration.application
                .PlatformUserResourceAdministrationService.KINDS) {
            MvcResult resource = mockMvc.perform(get(
                            "/internal/system-admin/v1/users/{userId}/resources", userId)
                            .param("kind", kind).param("pageSize", "1000")
                            .header("Authorization", "Bearer " + token(scope, false)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pageSize").value(100))
                    .andReturn();
            evidence.append(resource.getResponse().getContentAsString());
        }
        assertThat(evidence.toString()).doesNotContain(
                "PRIVATE_DOCUMENT_BODY", "private-file-name.md", "PRIVATE_MEMORY_CONTENT",
                "PRIVATE_MODEL_ERROR_SUMMARY", "PRIVATE_TOOL_ARGUMENTS", "PRIVATE_MCP_CIPHERTEXT",
                "PRIVATE_AUTOMATION_PROMPT", "private conversation title", "private task goal",
                "Task metadata");
        mockMvc.perform(get("/internal/system-admin/v1/users/{userId}/resources", userId)
                        .param("kind", "SQL_CONSOLE")
                        .header("Authorization", "Bearer " + token(scope, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYSTEM_ADMIN_USER_RESOURCE_KIND_INVALID"));
    }

    @Test
    void organizationAdministrationCreatesUpdatesAndEnqueuesDurableDeletion() throws Exception {
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "org-owner-" + UUID.randomUUID() + "@example.com",
                                "password", "OrganizationOwner123!",
                                "displayName", "Organization Owner"))))
                .andExpect(status().isOk())
                .andReturn();
        String ownerUserId = json(registration).at("/data/userId").asText();
        MvcResult memberRegistration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "org-member-" + UUID.randomUUID() + "@example.com",
                                "password", "OrganizationMember123!",
                                "displayName", "Organization Member"))))
                .andExpect(status().isOk())
                .andReturn();
        String memberUserId = json(memberRegistration).at("/data/userId").asText();
        String memberPersonalAccessToken = json(memberRegistration).at("/data/token").asText();
        UUID actorId = UUID.randomUUID();
        UUID createId = UUID.randomUUID();
        String slug = "admin-org-" + UUID.randomUUID().toString().substring(0, 12);
        String createBody = objectMapper.writeValueAsString(java.util.Map.of(
                "ownerUserId", ownerUserId, "name", "Admin Managed Organization",
                "slug", slug, "reason", "Approved organization provisioning"));

        MvcResult created = mockMvc.perform(post("/internal/system-admin/v1/organizations")
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:organizations:create", actorId, createId))
                        .header("X-Admin-Command-ID", createId)
                        .header("Idempotency-Key", "org-create-" + slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.result.creatorUserId").value(ownerUserId))
                .andReturn();
        String organizationId = json(created).at("/data/result/organizationId").asText();

        mockMvc.perform(post("/internal/system-admin/v1/organizations")
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:organizations:create", actorId, createId))
                        .header("X-Admin-Command-ID", createId)
                        .header("Idempotency-Key", "org-create-" + slug)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.organizationId").value(organizationId));

        UUID updateId = UUID.randomUUID();
        String updatedSlug = slug + "-updated";
        mockMvc.perform(patch("/internal/system-admin/v1/organizations/{organizationId}", organizationId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:organizations:update", actorId, updateId))
                        .header("X-Admin-Command-ID", updateId)
                        .header("Idempotency-Key", "org-update-" + organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", "Renamed Organization", "slug", updatedSlug,
                                "reason", "Approved organization rename"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.name").value("Renamed Organization"))
                .andExpect(jsonPath("$.data.result.slug").value(updatedSlug));

        UUID addMemberId = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/organizations/{organizationId}/members",
                        organizationId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:organizations:members:add", actorId, addMemberId))
                        .header("X-Admin-Command-ID", addMemberId)
                        .header("Idempotency-Key", "org-member-add-" + memberUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "userId", memberUserId, "role", "MEMBER",
                                "reason", "Approved member assignment"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.role").value("MEMBER"))
                .andExpect(jsonPath("$.data.result.status").value("ACTIVE"));

        assertThat(identityAdministration.organizationMembers(
                organizationId, 0, 1000, null, null, null).total()).isEqualTo(2);
        mockMvc.perform(get("/internal/system-admin/v1/organizations/{organizationId}/members",
                        organizationId)
                        .param("pageSize", "1000")
                        .header("Authorization", "Bearer " + token(
                                "system-admin:organizations:members:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].role").value("OWNER"))
                .andExpect(jsonPath("$.data.items[1].userId").value(memberUserId));

        UUID roleUpdateId = UUID.randomUUID();
        mockMvc.perform(patch(
                        "/internal/system-admin/v1/organizations/{organizationId}/members/{userId}",
                        organizationId, memberUserId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:organizations:members:role-update", actorId,
                                roleUpdateId))
                        .header("X-Admin-Command-ID", roleUpdateId)
                        .header("Idempotency-Key", "org-member-role-" + memberUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"reason\":\"Approved role change\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.role").value("ADMIN"));

        MvcResult switchedMember = mockMvc.perform(post(
                        "/api/v1/web/organizations/{organizationId}/switch", organizationId)
                        .header("Authorization", "Bearer " + memberPersonalAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantRole").value("ADMIN"))
                .andReturn();
        String staleAdminAccessToken = json(switchedMember).at("/data/token").asText();

        UUID transferId = UUID.randomUUID();
        mockMvc.perform(post(
                        "/internal/system-admin/v1/organizations/{organizationId}/ownership-transfers",
                        organizationId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:organizations:owner-transfer", actorId, transferId))
                        .header("X-Admin-Command-ID", transferId)
                        .header("Idempotency-Key", "org-owner-transfer-" + organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "newOwnerUserId", memberUserId,
                                "reason", "Approved ownership transfer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.creatorUserId").value(memberUserId));
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + staleAdminAccessToken))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_tenant_memberships
                WHERE tenant_id = ? AND tenant_role = 'OWNER' AND status = 'ACTIVE'
                """, Long.class, organizationId)).isEqualTo(1L);

        UUID ownerRoleChangeId = UUID.randomUUID();
        mockMvc.perform(patch(
                        "/internal/system-admin/v1/organizations/{organizationId}/members/{userId}",
                        organizationId, memberUserId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:organizations:members:role-update", actorId,
                                ownerRoleChangeId))
                        .header("X-Admin-Command-ID", ownerRoleChangeId)
                        .header("Idempotency-Key", "org-owner-role-denied-" + memberUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"reason\":\"Verify owner protection\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("FAILED"))
                .andExpect(jsonPath("$.data.safeErrorCode")
                        .value("SYSTEM_ADMIN_ORGANIZATION_OWNER_TRANSFER_REQUIRED"));

        UUID removeId = UUID.randomUUID();
        mockMvc.perform(delete(
                        "/internal/system-admin/v1/organizations/{organizationId}/members/{userId}",
                        organizationId, ownerUserId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:organizations:members:remove", actorId, removeId))
                        .header("X-Admin-Command-ID", removeId)
                        .header("Idempotency-Key", "org-member-remove-" + ownerUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Approved member removal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("SUSPENDED"));

        mockMvc.perform(get("/internal/system-admin/v1/organizations/{organizationId}/members",
                        organizationId)
                        .param("status", "SUSPENDED")
                        .header("Authorization", "Bearer " + token(
                                "system-admin:organizations:members:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value(ownerUserId));

        UUID deleteId = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/organizations/{organizationId}/deletion-jobs",
                        organizationId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:organizations:delete", actorId, deleteId))
                        .header("X-Admin-Command-ID", deleteId)
                        .header("Idempotency-Key", "org-delete-" + organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Approved organization decommission\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("DELETING"))
                .andExpect(jsonPath("$.data.result.deletionRequestedAt").exists());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM platform_tenants WHERE id = ?", String.class, organizationId))
                .isEqualTo("DELETING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_organization_cleanup_jobs WHERE organization_id = ?",
                Long.class, organizationId)).isEqualTo(1L);
        mockMvc.perform(get("/internal/system-admin/v1/cleanup-jobs")
                        .param("kind", "ORGANIZATION").param("query", organizationId)
                        .header("Authorization", "Bearer " + token(
                                "system-admin:cleanup:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].state").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].totalSteps").value(13));
        mockMvc.perform(get("/internal/system-admin/v1/cleanup-jobs/ORGANIZATION/{id}",
                        organizationId)
                        .header("Authorization", "Bearer " + token(
                                "system-admin:cleanup:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps.length()").value(13));
        mockMvc.perform(get("/internal/system-admin/v1/cleanup-jobs/overview")
                        .header("Authorization", "Bearer " + token(
                                "system-admin:cleanup:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pending").value(greaterThanOrEqualTo(1)));
        jdbc.update("DELETE FROM platform_organization_cleanup_steps WHERE organization_id = ?",
                organizationId);
        jdbc.update("DELETE FROM platform_organization_cleanup_jobs WHERE organization_id = ?",
                organizationId);
        jdbc.update("DELETE FROM platform_tenant_memberships WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_tenants WHERE id = ?", organizationId);
    }

    @Test
    void userAdministrationCommandsAreIdempotentActivatableAndLifecycleSafe() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID createCommandId = UUID.randomUUID();
        String login = "managed-" + UUID.randomUUID() + "@example.com";
        String organizationSlug = "managed-" + UUID.randomUUID().toString().substring(0, 12);
        String createBody = objectMapper.writeValueAsString(java.util.Map.of(
                "loginName", login,
                "displayName", "Managed User",
                "organizationName", "Managed Personal Organization",
                "organizationSlug", organizationSlug,
                "reason", "Provision user for support request"));

        MvcResult created = mockMvc.perform(post("/internal/system-admin/v1/users")
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:create", actorId, createCommandId))
                        .header("X-Admin-Command-ID", createCommandId)
                        .header("Idempotency-Key", "create-" + login)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.status").value("PENDING_ACTIVATION"))
                .andExpect(jsonPath("$.data.activationToken").isNotEmpty())
                .andReturn();
        JsonNode createdData = json(created).path("data");
        String userId = createdData.path("result").path("userId").asText();
        String activationToken = createdData.path("activationToken").asText();

        mockMvc.perform(post("/internal/system-admin/v1/users")
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:create", actorId, createCommandId))
                        .header("X-Admin-Command-ID", createCommandId)
                        .header("Idempotency-Key", "create-" + login)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.userId").value(userId))
                .andExpect(jsonPath("$.data.activationToken").doesNotExist());

        UUID duplicateSlugCommandId = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users")
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:create", actorId, duplicateSlugCommandId))
                        .header("X-Admin-Command-ID", duplicateSlugCommandId)
                        .header("Idempotency-Key", "duplicate-slug-" + login)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "loginName", "duplicate-" + login,
                                "organizationSlug", organizationSlug,
                                "reason", "Verify duplicate organization conflict"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("FAILED"))
                .andExpect(jsonPath("$.data.safeErrorCode").value("ORGANIZATION_SLUG_CONFLICT"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", login,
                                "password", "CannotStealPendingUser123!",
                                "displayName", "Collision"))))
                .andExpect(status().isConflict());

        MvcResult activated = mockMvc.perform(post("/api/v1/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "activationToken", activationToken,
                                "password", "ManagedUserPassword123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andReturn();
        String userAccessToken = json(activated).at("/data/token").asText();
        mockMvc.perform(post("/api/v1/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "activationToken", activationToken,
                                "password", "ManagedUserPassword123!"))))
                .andExpect(status().isUnauthorized());

        UUID suspendCommandId = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/suspend", userId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:suspend", actorId, suspendCommandId))
                        .header("X-Admin-Command-ID", suspendCommandId)
                        .header("Idempotency-Key", "suspend-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Investigate confirmed account compromise\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.result.sessionsRevoked").value(true));
        UUID repeatedSuspendCommandId = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/suspend", userId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:suspend", actorId, repeatedSuspendCommandId))
                        .header("X-Admin-Command-ID", repeatedSuspendCommandId)
                        .header("Idempotency-Key", "repeated-suspend-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Confirm lifecycle conflict is durable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("FAILED"))
                .andExpect(jsonPath("$.data.safeErrorCode")
                        .value("SYSTEM_ADMIN_USER_STATUS_CONFLICT"));
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", login, "password", "ManagedUserPassword123!"))))
                .andExpect(status().isUnauthorized());

        UUID restoreCommandId = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/restore", userId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:restore", actorId, restoreCommandId))
                        .header("X-Admin-Command-ID", restoreCommandId)
                        .header("Idempotency-Key", "restore-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Security review completed successfully\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", login, "password", "ManagedUserPassword123!"))))
                .andExpect(status().isOk());

        UUID preflightCommandId = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/deletion-requests", userId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:deletion-preflight", actorId, preflightCommandId))
                        .header("X-Admin-Command-ID", preflightCommandId)
                        .header("Idempotency-Key", "delete-preflight-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"User requested account deletion\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.eligible").value(true))
                .andExpect(jsonPath("$.data.result.physicalDeletionEnabled").value(true));
        mockMvc.perform(get("/internal/system-admin/v1/commands/{commandId}", preflightCommandId)
                        .header("Authorization", "Bearer " + token(
                                "system-admin:commands:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commandId").value(preflightCommandId.toString()));

        UUID conflictingCommandId = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users")
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:create", actorId, conflictingCommandId))
                        .header("X-Admin-Command-ID", conflictingCommandId)
                        .header("Idempotency-Key", "create-" + login)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "loginName", "different-" + login,
                                "reason", "Different request reusing a key"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_ADMIN_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void durableUserCleanupSurvivesHandoffAndProducesMinimalTombstone() throws Exception {
        String login = "erase-" + UUID.randomUUID() + "@example.com";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", login,
                                "password", "EraseUserPassword123!",
                                "displayName", "Erase User"))))
                .andExpect(status().isOk()).andReturn();
        String userId = json(registration).at("/data/userId").asText();
        String organizationId = json(registration).at("/data/tenantId").asText();
        UUID actorId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO platform_conversations(id, tenant_id, user_id, title, status,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'private conversation', 'ACTIVE', clock_timestamp(), clock_timestamp())
                """, UUID.randomUUID().toString(), organizationId, userId);
        jdbc.update("""
                INSERT INTO platform_knowledge_documents(id, owner_id, name, content_type,
                    storage_location, status, created_at, updated_at)
                VALUES (?, ?, 'private.txt', 'text/plain', 'inline:private', 'READY',
                    clock_timestamp(), clock_timestamp())
                """, UUID.randomUUID().toString(), userId);
        jdbc.update("""
                INSERT INTO platform_memory_candidates(id, scope_type, scope_id, kind,
                    source_id, source_type, content, confidence, dedupe_key, state, created_at)
                VALUES (?, 'USER', ?, 'PREFERENCE', 'source', 'MESSAGE', 'private preference',
                    0.9, ?, 'ACCEPTED', clock_timestamp())
                """, UUID.randomUUID().toString(), userId, "erase-" + UUID.randomUUID());

        UUID suspendCommand = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/suspend", userId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:suspend", actorId, suspendCommand))
                        .header("X-Admin-Command-ID", suspendCommand)
                        .header("Idempotency-Key", "erase-suspend-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Approved account erasure\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"));

        UUID deleteCommand = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/deletion-jobs", userId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:delete", actorId, deleteCommand))
                        .header("X-Admin-Command-ID", deleteCommand)
                        .header("Idempotency-Key", "erase-delete-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Approved account erasure\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.cleanupState").value("PENDING"))
                .andExpect(jsonPath("$.data.result.physicalDeletionEnabled").value(true));

        mockMvc.perform(get("/internal/system-admin/v1/cleanup-jobs")
                        .param("kind", "USER").param("query", userId)
                        .header("Authorization", "Bearer " + token(
                                "system-admin:cleanup:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].commandId").value(deleteCommand.toString()))
                .andExpect(jsonPath("$.data.items[0].totalSteps").value(15));

        makeOnlyUserCleanupDue(userId);
        assertThat(userCleanup.runOnce("user-cleanup-a", 60)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM platform_user_cleanup_jobs WHERE user_id = ?", String.class, userId))
                .isEqualTo("RETRY");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM platform_tenants WHERE id = ?", String.class, organizationId))
                .isEqualTo("DELETING");

        jdbc.update("""
                UPDATE platform_organization_cleanup_jobs
                   SET retention_not_before = clock_timestamp(), next_attempt_at = clock_timestamp()
                 WHERE organization_id = ?
                """, organizationId);
        assertThat(organizationCleanup.runOnce("organization-cleanup-a", 60)).isTrue();
        jdbc.update("UPDATE platform_user_cleanup_jobs SET next_attempt_at = clock_timestamp() WHERE user_id = ?",
                userId);
        assertThat(userCleanup.runOnce("user-cleanup-b", 60)).isTrue();

        mockMvc.perform(get("/internal/system-admin/v1/users/{userId}/deletion-jobs/current", userId)
                        .header("Authorization", "Bearer " + token(
                                "system-admin:users:deletion:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.state").value("COMPLETED"))
                .andExpect(jsonPath("$.data.steps.length()").value(15))
                .andExpect(jsonPath("$.data.steps[14].state").value("COMPLETED"));

        var tombstone = jdbc.queryForMap("""
                SELECT external_id, display_name, status, deleted_at
                FROM platform_users WHERE id = ?
                """, userId);
        assertThat(tombstone).containsEntry("status", "DELETED")
                .containsEntry("display_name", "Deleted User");
        assertThat(tombstone.get("external_id").toString()).startsWith("deleted-");
        assertThat(tombstone.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_user_credentials WHERE user_id = ?", Long.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_knowledge_documents WHERE owner_id = ?", Long.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_memory_candidates
                WHERE scope_type = 'USER' AND scope_id = ?
                """, Long.class, userId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_conversations WHERE user_id = ?", Long.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM platform_tenants WHERE id = ?", String.class, organizationId))
                .isEqualTo("DELETED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_users WHERE external_id = ?", Long.class, login))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(110L);
    }

    @Test
    void userCleanupClaimIsSingleOwnerFencedAndUnknownCanBlockImmediately() throws Exception {
        String login = "fenced-" + UUID.randomUUID() + "@example.com";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", login, "password", "FencedUserPassword123!",
                                "displayName", "Fenced User"))))
                .andExpect(status().isOk()).andReturn();
        String userId = json(registration).at("/data/userId").asText();
        UUID actorId = UUID.randomUUID();
        UUID suspendCommand = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/suspend", userId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:suspend", actorId, suspendCommand))
                        .header("X-Admin-Command-ID", suspendCommand)
                        .header("Idempotency-Key", "fenced-suspend-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Prepare fenced cleanup test\"}"))
                .andExpect(status().isOk());
        UUID deleteCommand = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/deletion-jobs", userId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:delete", actorId, deleteCommand))
                        .header("X-Admin-Command-ID", deleteCommand)
                        .header("Idempotency-Key", "fenced-delete-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Prepare fenced cleanup test\"}"))
                .andExpect(status().isOk());

        makeOnlyUserCleanupDue(userId);
        var first = userCleanupControl.claimNext(
                new UserCleanupApplicationApi.ClaimNextCommand("worker-one", 5)).orElseThrow();
        assertThat(userCleanupControl.claimNext(
                new UserCleanupApplicationApi.ClaimNextCommand("worker-two", 5))).isEmpty();
        jdbc.update("""
                UPDATE platform_user_cleanup_jobs
                   SET lease_until = clock_timestamp() - interval '1 second'
                 WHERE user_id = ?
                """, userId);
        var recovered = userCleanupControl.claimNext(
                new UserCleanupApplicationApi.ClaimNextCommand("worker-two", 5)).orElseThrow();
        assertThat(recovered.job().fencingToken()).isGreaterThan(first.job().fencingToken());
        assertThatThrownBy(() -> userCleanupControl.heartbeat(
                new UserCleanupApplicationApi.HeartbeatCommand(userId, "worker-one",
                        first.job().leaseToken(), first.job().fencingToken(), 5)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("USER_CLEANUP_CLAIM_LOST"));

        var blocked = userCleanupControl.block(new UserCleanupApplicationApi.BlockCommand(
                userId, UserCleanupStepKey.AUTH_FREEZE, "worker-two",
                recovered.job().leaseToken(), recovered.job().fencingToken(),
                "UNKNOWN_EFFECT_BLOCKER", "UNKNOWN effect requires manual reconciliation"));
        assertThat(blocked.state().name()).isEqualTo("BLOCKED");
    }

    @Test
    void deletionRequestCannotSilentlyTransferNonEmptyOrganizationOwnership() throws Exception {
        MvcResult ownerRegistration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "owner-" + UUID.randomUUID() + "@example.com",
                                "password", "OwnerPassword123!", "displayName", "Owner"))))
                .andExpect(status().isOk()).andReturn();
        String ownerId = json(ownerRegistration).at("/data/userId").asText();
        String organizationId = json(ownerRegistration).at("/data/tenantId").asText();
        MvcResult memberRegistration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "member-" + UUID.randomUUID() + "@example.com",
                                "password", "MemberPassword123!", "displayName", "Member"))))
                .andExpect(status().isOk()).andReturn();
        String memberId = json(memberRegistration).at("/data/userId").asText();
        jdbc.update("""
                INSERT INTO platform_tenant_memberships(
                    tenant_id, user_id, tenant_role, status, joined_at, updated_at)
                VALUES (?, ?, 'MEMBER', 'ACTIVE', clock_timestamp(), clock_timestamp())
                """, organizationId, memberId);

        UUID actorId = UUID.randomUUID();
        UUID suspendCommand = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/suspend", ownerId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:suspend", actorId, suspendCommand))
                        .header("X-Admin-Command-ID", suspendCommand)
                        .header("Idempotency-Key", "owner-suspend-" + ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Prepare ownership blocker test\"}"))
                .andExpect(status().isOk());
        UUID deleteCommand = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/users/{userId}/deletion-jobs", ownerId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:users:delete", actorId, deleteCommand))
                        .header("X-Admin-Command-ID", deleteCommand)
                        .header("Idempotency-Key", "owner-delete-" + ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Verify explicit ownership transfer gate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("FAILED"))
                .andExpect(jsonPath("$.data.safeErrorCode").value("USER_DELETION_BLOCKED"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_user_cleanup_jobs WHERE user_id = ?", Long.class, ownerId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM platform_users WHERE id = ?", String.class, ownerId))
                .isEqualTo("SUSPENDED");
    }

    @Test
    void systemAdministratorSynchronizesReviewsAndPublishesOfficialRegistryCandidate() throws Exception {
        String registryName = "io.github.example/admin-" + UUID.randomUUID().toString().substring(0, 8);
        when(mcpRegistryGateway.fetchPage(anyString(), any(), any(), anyInt())).thenReturn(
                new McpRegistryGateway.RegistryPage(List.of(new McpRegistryGateway.RegistryServer(
                        registryName, "1.0.0", McpRegistryStatus.ACTIVE, null,
                        "Admin Registry", "Admin Registry fixture",
                        "https://schema.example/server.json",
                        "https://github.com/example/admin-registry",
                        "{\"server\":{\"name\":\"" + registryName
                                + "\",\"version\":\"1.0.0\"}}",
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-09-02T00:00:00Z"),
                        List.of(new McpRegistrySnapshot.RemoteTransport(
                                "https://mcp.example.com/rpc", "{}", "{}", false)),
                        McpRegistryCompatibility.SUPPORTED_REMOTE, null)), null));
        UUID actor = UUID.randomUUID();
        UUID syncCommand = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/mcp-registry/sync-jobs")
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:mcp-registry:sync", actor, syncCommand))
                        .header("X-Admin-Command-ID", syncCommand)
                        .header("Idempotency-Key", "registry-sync-" + registryName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Refresh official Registry review evidence\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.state").value("PENDING"));
        assertThat(mcpRegistry.runOnce("system-admin-registry-test")).isTrue();

        MvcResult listed = mockMvc.perform(get("/internal/system-admin/v1/mcp-registry/candidates")
                        .param("query", registryName)
                        .header("Authorization", "Bearer " + token(
                                "system-admin:mcp-registry:read", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].reviewState").value("PENDING_REVIEW"))
                .andReturn();
        String candidateId = json(listed).at("/data/items/0/id").asText();
        UUID approvalCommand = UUID.randomUUID();
        mockMvc.perform(post("/internal/system-admin/v1/mcp-registry/candidates/{id}/approvals",
                        candidateId)
                        .header("Authorization", "Bearer " + commandToken(
                                "system-admin:mcp-registry:approve", actor, approvalCommand))
                        .header("X-Admin-Command-ID", approvalCommand)
                        .header("Idempotency-Key", "registry-approval-" + candidateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Reviewed repository and fixed HTTPS endpoint\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.result.reviewState").value("APPROVED"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_mcp_marketplace_entries
                 WHERE registry_name = ? AND source_type = 'OFFICIAL_REGISTRY'
                   AND trust_tier = 'REGISTRY_VERIFIED'
                """, Long.class, registryName)).isEqualTo(1L);
    }

    @Test
    void v1053BackupRestoresCleanupControlPlaneAndSkillRegistryIntoDisposableDatabase() throws Exception {
        String verifyDatabase = "user_cleanup_restore_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);
        try {
            var result = POSTGRES.execInContainer("sh", "-ec", """
                    pg_dump -U spaceagent -d spaceagent_system_admin -Fc -f /tmp/user-cleanup.dump
                    createdb -U spaceagent %s
                    pg_restore -U spaceagent -d %s --no-owner --no-privileges --exit-on-error /tmp/user-cleanup.dump
                    psql -U spaceagent -d %s -X -q -A -t -v ON_ERROR_STOP=1 -F '|' -c \
                      "SELECT (SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1),
                              to_regclass('platform_user_cleanup_jobs') IS NOT NULL,
                              to_regclass('platform_user_cleanup_steps') IS NOT NULL,
                              to_regclass('platform_project_execution_context_snapshots') IS NOT NULL,
                              to_regclass('platform_project_intake_jobs') IS NOT NULL,
                              to_regclass('platform_project_coding_jobs') IS NOT NULL,
                              to_regclass('platform_project_run_handoffs') IS NOT NULL,
                              EXISTS(SELECT 1 FROM information_schema.columns
                                WHERE table_name='platform_model_call_ledger'
                                  AND column_name='first_chunk_ms'),
                              EXISTS(SELECT 1 FROM information_schema.columns
                                WHERE table_name='platform_tasks'
                                  AND column_name='conversation_id'),
                              EXISTS(SELECT 1 FROM information_schema.columns
                                WHERE table_name='platform_agent_runs'
                                  AND column_name='chat_task_id'),
                              EXISTS(SELECT 1 FROM information_schema.columns
                                WHERE table_name='platform_task_plans'
                                  AND column_name='conversation_id'),
                              EXISTS(SELECT 1 FROM information_schema.columns
                                WHERE table_name='platform_task_plans'
                                  AND column_name='source_agent_run_id'),
                              to_regclass('platform_skill_definitions') IS NOT NULL,
                              to_regclass('platform_skill_versions') IS NOT NULL,
                              (SELECT count(*) >= 3 FROM pg_indexes
                                WHERE indexname LIKE 'idx_platform_user_cleanup%%')"
                    """.formatted(verifyDatabase, verifyDatabase, verifyDatabase));
            assertThat(result.getExitCode()).isZero();
            assertThat(result.getStdout().trim())
                    .isEqualTo("1098|t|t|t|t|t|t|t|t|t|t|t|t|t|t");
        } finally {
            POSTGRES.execInContainer("dropdb", "-U", "spaceagent", "--if-exists", verifyDatabase);
        }
    }

    @Test
    void presenceTracksAuthorizedSessionsAndAdminLifecycleRevokesAccess() throws Exception {
        String username = "presence-" + UUID.randomUUID() + "@example.com";
        JsonNode auth = json(mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("username", username,
                        "password", "OriginalPassword123!", "displayName", "Presence"))))
                .andExpect(status().isOk()).andReturn()).path("data");
        String access = auth.path("token").asText();
        String user = auth.path("userId").asText();
        String tenant = auth.path("tenantId").asText();
        String session = json(mockMvc.perform(post("/api/v1/presence/heartbeat")
                .header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientType\":\"WEB\"}"))
                .andExpect(status().isOk()).andReturn()).at("/data/sessionId").asText();
        mockMvc.perform(post("/api/v1/presence/heartbeat")) .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/system-admin/v1/presence").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/system-admin/v1/presence")
                .header("Authorization", "Bearer " + token("system-admin:users:read", false)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/system-admin/v1/presence")
                .header("Authorization", "Bearer " + token("system-admin:presence:read", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.coverage").value("HEARTBEAT_CLIENTS_ONLY"))
                .andExpect(jsonPath("$.data.onlineUsers").value(greaterThanOrEqualTo(1)));

        JsonNode rotated = json(mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", auth.path("refreshToken").asText()))))
                .andExpect(status().isOk()).andReturn()).path("data");
        mockMvc.perform(post("/api/v1/presence/heartbeat").header("Authorization", "Bearer " + rotated.path("token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value(session));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_presence_leases WHERE user_id=?", Long.class, user)).isEqualTo(1);
        var presenceRepository = new com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityPresenceRepository(jdbc);
        var baselinePresence = presenceRepository.counts(Instant.now());
        jdbc.update("UPDATE platform_presence_leases SET expires_at=clock_timestamp()-interval '5 seconds' WHERE user_id=?", user);
        assertThat(presenceRepository.counts(Instant.now()).sessions()).isEqualTo(baselinePresence.sessions()-1);
        // Even an internal caller cannot associate a valid login-session id with a foreign tenant.
        assertThat(presenceRepository.heartbeat(UUID.fromString(session), user, UUID.randomUUID().toString(),
                0, "a".repeat(64), "WEB", Instant.now(), Instant.now().plusSeconds(90))).isFalse();
        mockMvc.perform(post("/api/v1/presence/heartbeat").header("Authorization", "Bearer " + rotated.path("token").asText()))
                .andExpect(status().isOk());

        UUID actor = UUID.randomUUID(), update = UUID.randomUUID(), revoke = UUID.randomUUID();
        mockMvc.perform(patch("/internal/system-admin/v1/users/" + user)
                .header("Authorization", "Bearer " + commandToken("system-admin:users:update", actor, update))
                .header("X-Admin-Command-ID", update).header("Idempotency-Key", update.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Updated\",\"reason\":\"Requested correction\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT display_name FROM platform_users WHERE id=?", String.class, user)).isEqualTo("Updated");
        for (int i = 0; i < 2; i++) mockMvc.perform(post("/internal/system-admin/v1/users/" + user + "/session-revocations")
                .header("Authorization", "Bearer " + commandToken("system-admin:users:sessions:revoke", actor, revoke))
                .header("X-Admin-Command-ID", revoke).header("Idempotency-Key", revoke.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Security test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT access_version FROM platform_users WHERE id=?", Long.class, user)).isEqualTo(1);
        assertThat(presenceRepository.counts(Instant.now()).sessions()).isEqualTo(baselinePresence.sessions()-1);
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/presence/heartbeat").header("Authorization", "Bearer " + rotated.path("token").asText()))
                .andExpect(status().isUnauthorized());

        UUID reset = UUID.randomUUID();
        JsonNode resetResponse = json(mockMvc.perform(post("/internal/system-admin/v1/users/" + user + "/password-resets")
                .header("Authorization", "Bearer " + commandToken("system-admin:users:password:reset", actor, reset))
                .header("X-Admin-Command-ID", reset).header("Idempotency-Key", reset.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"User recovery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("SUCCEEDED")).andReturn()).path("data");
        String secret = resetResponse.path("passwordResetToken").asText();
        assertThat(secret).hasSize(43);
        String result = json(mockMvc.perform(get("/internal/system-admin/v1/commands/" + reset)
                .header("Authorization", "Bearer " + token("system-admin:commands:read", false)))
                .andExpect(status().isOk()).andReturn()).toString();
        assertThat(result).doesNotContain(secret);
        var resetBody = objectMapper.writeValueAsString(java.util.Map.of("resetToken", secret, "password", "NewPassword456!"));
        mockMvc.perform(post("/api/v1/auth/password-reset").contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/password-reset").contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("username", username, "password", "NewPassword456!"))))
                .andExpect(status().isOk());
    }

    @Test void heartbeatDeduplicatesUsersAcrossSessionsAndLogoutRemovesOnlyThatSession() throws Exception {
        var repository = new com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityPresenceRepository(jdbc);
        var before = repository.counts(Instant.now());
        String username = "heartbeat-multi-" + UUID.randomUUID() + "@example.com";
        String body = objectMapper.writeValueAsString(java.util.Map.of("username", username,
                "password", "PresencePassword123!", "displayName", "Heartbeat fixture"));
        JsonNode a = json(mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn()).path("data");
        JsonNode b = json(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("username", username, "password", "PresencePassword123!"))))
                .andExpect(status().isOk()).andReturn()).path("data");
        for (JsonNode auth : java.util.List.of(a, a, b)) {
            mockMvc.perform(post("/api/v1/presence/heartbeat").header("Authorization", "Bearer " + auth.path("token").asText())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"clientType\":\"WEB\"}"))
                    .andExpect(status().isOk());
        }
        assertThat(repository.counts(Instant.now()).users()).isEqualTo(before.users() + 1);
        assertThat(repository.counts(Instant.now()).sessions()).isEqualTo(before.sessions() + 2);
        for (int i = 0; i < 2; i++) {
            JsonNode auth = i == 0 ? b : a;
            mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + auth.path("token").asText())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", auth.path("refreshToken").asText()))))
                    .andExpect(status().isOk());
            assertThat(repository.counts(Instant.now()).users()).isEqualTo(before.users() + (i == 0 ? 1 : 0));
            assertThat(repository.counts(Instant.now()).sessions()).isEqualTo(before.sessions() + (i == 0 ? 1 : 0));
        }
    }

    @Test void presenceMigrationUpgradesExistingRefreshTokensWithoutGuessingOnlineStatus() {
        var ds = com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, "presence_upgrade");
        var original = org.flywaydb.core.Flyway.configure().dataSource(ds)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime").target("1084").load();
        original.migrate();
        var old = new JdbcTemplate(ds);
        String tenant = UUID.randomUUID().toString(), user = UUID.randomUUID().toString();
        old.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES (?,'Upgrade','upgrade','ACTIVE',clock_timestamp(),clock_timestamp())", tenant);
        old.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES (?,?,'upgrade@example.com','Upgrade',clock_timestamp(),clock_timestamp())", user, tenant);
        old.update("INSERT INTO platform_refresh_tokens(token_hash,user_id,tenant_id,tenant_role,expires_at,created_at) VALUES (?,?,?,'OWNER',clock_timestamp()+interval '1 day',clock_timestamp())", "b".repeat(64), user, tenant);
        assertThat(old.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='presence_upgrade' AND table_name='platform_users' AND column_name='access_version'", Long.class)).isZero();
        var upgraded = org.flywaydb.core.Flyway.configure().dataSource(ds)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime").load();
        upgraded.migrate();
        upgraded.migrate();
        var presence = new com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityPresenceRepository(old);
        assertThat(presence.counts(Instant.now()).observedSessions()).isZero();
        assertThat(old.queryForObject("SELECT access_version FROM platform_users WHERE id=?", Long.class, user)).isZero();
        assertThat(old.queryForObject("SELECT session_id FROM platform_refresh_tokens WHERE user_id=?", UUID.class, user)).isNotNull();
        assertThat(old.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("1098");
    }

    @Test void organizationIndexSupportsUnfilteredAndFilteredReads() throws Exception {
        String name = "Org SQL " + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("username", UUID.randomUUID()+"@example.com",
                                "password", "OrganizationPassword123!", "displayName", name))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/internal/system-admin/v1/organizations")
                        .header("Authorization", "Bearer " + token("system-admin:organizations:read", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(1)));
        mockMvc.perform(get("/internal/system-admin/v1/organizations").param("query", name).param("status", "ACTIVE")
                        .header("Authorization", "Bearer " + token("system-admin:organizations:read", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }

    private void makeOnlyUserCleanupDue(String userId) {
        // Shared fixture jobs must not compete; use the database clock used by the claimant.
        jdbc.update("UPDATE platform_user_cleanup_jobs SET next_attempt_at=clock_timestamp()+interval '1 day' WHERE user_id<>? AND state IN ('PENDING','RETRY')", userId);
        jdbc.update("UPDATE platform_user_cleanup_jobs SET retention_not_before=clock_timestamp()-interval '1 second',next_attempt_at=clock_timestamp()-interval '1 second' WHERE user_id=?", userId);
    }

    private String token(String scope, boolean tenantClaim) {
        Instant now = Instant.now();
        var builder = Jwts.builder().id(UUID.randomUUID().toString())
                .issuer("spaceagent-platform-admin")
                .audience().add("spaceagent-platform-admin-internal").and()
                .subject("platform-admin-server")
                .claim("actor_id", UUID.randomUUID().toString())
                .claim("scope", scope)
                .claim("request_id", UUID.randomUUID().toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(45)));
        if (tenantClaim) builder.claim("tenant_id", UUID.randomUUID().toString());
        return builder.signWith(Keys.hmacShaKeyFor(SYSTEM_SECRET.getBytes(StandardCharsets.UTF_8)),
                Jwts.SIG.HS256).compact();
    }

    private String commandToken(String scope, UUID actorId, UUID commandId) {
        Instant now = Instant.now();
        return Jwts.builder().id(UUID.randomUUID().toString())
                .issuer("spaceagent-platform-admin")
                .audience().add("spaceagent-platform-admin-internal").and()
                .subject("platform-admin-server")
                .claim("actor_id", actorId.toString())
                .claim("scope", scope)
                .claim("request_id", UUID.randomUUID().toString())
                .claim("command_id", commandId.toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(45)))
                .signWith(Keys.hmacShaKeyFor(SYSTEM_SECRET.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256).compact();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
