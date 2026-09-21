package com.spaceagent.platform.identity;

import com.spaceagent.platform.agent.infrastructure.persistence.PostgresAgentCleanupService;
import com.spaceagent.platform.artifact.infrastructure.persistence.PostgresArtifactCleanupService;
import com.spaceagent.platform.automation.infrastructure.persistence.PostgresAutomationCleanupService;
import com.spaceagent.platform.conversation.infrastructure.persistence.PostgresConversationCleanupService;
import com.spaceagent.platform.governance.infrastructure.persistence.PostgresGovernanceCleanupService;
import com.spaceagent.platform.identity.api.LeaveOrganizationCommand;
import com.spaceagent.platform.identity.api.ProvisionPersonalOrganizationCommand;
import com.spaceagent.platform.identity.application.OrganizationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationCleanupApplicationService;
import com.spaceagent.platform.identity.domain.OrganizationCleanupJobState;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepState;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityCleanupService;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresOrganizationCleanupRepository;
import com.spaceagent.platform.inference.infrastructure.persistence.PostgresInferenceCleanupService;
import com.spaceagent.platform.integration.application.OrganizationCleanupCoordinator;
import com.spaceagent.platform.integration.application.OrganizationCleanupExecutionCoordinator;
import com.spaceagent.platform.memory.infrastructure.persistence.PostgresMemoryCleanupService;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.ProjectIntakeWorkspaceGateway;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceProvisioningGateway;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectCleanupService;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresSourceRepositoryRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresWorkspaceRepository;
import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresRuntimeCleanupService;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresToolingCleanupService;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PlatformOrganizationCleanupExecutionPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_cleanup_execution")
                    .withUsername("spaceagent").withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
    }

    @Test
    void orderedExecutionPurgesOrganizationStateAndRetainsUserScopedState() {
        var dataSource = dataSource();
        var jdbc = new JdbcTemplate(dataSource);
        var identities = new PostgresIdentityRepository(jdbc);
        TimeProvider time = Instant::now;
        var cleanup = new OrganizationCleanupApplicationService(
                identities, new PostgresOrganizationCleanupRepository(jdbc), time, 0, 5, 1);
        var organizations = new OrganizationApplicationService(
                identities, new UuidGenerator(), time, cleanup);
        var target = organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        "cleanup-execution@example.com", "Cleanup", "Cleanup Org", "cleanup-execution"));
        var foreign = organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        "cleanup-foreign@example.com", "Foreign", "Foreign Org", "cleanup-foreign"));
        seed(jdbc, target.organization().id(), target.user().id(), "a");
        seed(jdbc, foreign.organization().id(), foreign.user().id(), "b");
        jdbc.update("""
                INSERT INTO platform_source_repositories
                  (id,project_id,tenant_id,provider_repository_id,display_name,default_branch,repository_type,state,visibility,created_by,created_at,updated_at)
                VALUES (gen_random_uuid(),'10000000-0000-4000-8000-000000000001',?,'managed:test','Retained root','main','GENERIC','READY','INTERNAL',?,now(),now())
                """, target.organization().id(), target.user().id());
        organizations.leaveOrganization(new LeaveOrganizationCommand(
                target.user().id(), target.organization().id()));

        WorkspaceProvisioningGateway noManagedWorkspace = new WorkspaceProvisioningGateway() {
            @Override public ProvisionedWorkspace provision(
                    Workspace workspace, SourceRepository source, String accessToken) {
                throw new UnsupportedOperationException();
            }
            @Override public void cleanup(Workspace workspace, SourceRepository source) {
            }
        };
        var control = new OrganizationCleanupCoordinator(cleanup);
        var execution = new OrganizationCleanupExecutionCoordinator(
                control, cleanup,
                new PostgresAutomationCleanupService(jdbc),
                new PostgresRuntimeCleanupService(jdbc),
                new PostgresArtifactCleanupService(jdbc),
                new PostgresConversationCleanupService(jdbc),
                new PostgresToolingCleanupService(jdbc),
                new PostgresMemoryCleanupService(jdbc),
                new PostgresProjectCleanupService(
                        jdbc, new PostgresWorkspaceRepository(jdbc),
                        new PostgresSourceRepositoryRepository(jdbc), noManagedWorkspace,
                        mock(ProjectIntakeWorkspaceGateway.class)),
                new PostgresAgentCleanupService(jdbc),
                new PostgresInferenceCleanupService(jdbc),
                new PostgresGovernanceCleanupService(jdbc),
                new PostgresIdentityCleanupService(jdbc, identities, time));

        assertTrue(execution.runOnce("cleanup-worker", 60));
        assertEquals(OrganizationCleanupJobState.BLOCKED, cleanup.findJob(target.organization().id()).orElseThrow().state());
        assertEquals("PROJECT_STORAGE_ADMIN_REQUIRED", cleanup.findJob(target.organization().id()).orElseThrow().lastErrorCode());
        assertEquals(1L, count(jdbc, "platform_source_repositories", "tenant_id", target.organization().id()));
        assertEquals(1L, count(jdbc, "platform_conversations", "tenant_id", target.organization().id()));
        var commands = new com.spaceagent.platform.integration.infrastructure.persistence.PostgresPlatformSystemAdministrationCommandRepository(jdbc);
        execution.setStorageDeletionAuthorization(commands);
        var commandId = java.util.UUID.randomUUID();
        commands.insert(new com.spaceagent.platform.integration.domain.PlatformSystemAdministrationCommandRepository.CommandRecord(
                commandId, "a".repeat(64), "ORGANIZATION_DELETE", "b".repeat(64), java.util.UUID.randomUUID(),
                target.organization().id(), "SUCCEEDED", "{}", null, Instant.now(), Instant.now(), Instant.now()));
        cleanup.resumeStorageDeletionAfterAdminApproval(target.organization().id());
        assertTrue(execution.runOnce("cleanup-worker", 60));
        assertFalse(execution.runOnce("cleanup-worker", 60));
        assertEquals(TenantStatus.DELETED,
                identities.findTenantById(target.organization().id()).orElseThrow().status());
        assertEquals(OrganizationCleanupJobState.COMPLETED,
                cleanup.findJob(target.organization().id()).orElseThrow().state());
        assertTrue(cleanup.findSteps(target.organization().id()).stream()
                .allMatch(step -> step.state() == OrganizationCleanupStepState.COMPLETED));
        assertFalse(identities.findMembership(
                target.organization().id(), target.user().id()).isPresent());
        assertTrue(identities.findUserById(target.user().id()).isPresent());
        assertEquals(1L, count(jdbc, "platform_knowledge_documents", "owner_id", target.user().id()));
        assertEquals(1L, count(jdbc, "platform_consolidated_memories", "scope_id", target.user().id()));
        assertEquals(0L, count(jdbc, "platform_conversations", "tenant_id", target.organization().id()));
        assertEquals(0L, count(jdbc, "platform_projects", "tenant_id", target.organization().id()));
        assertEquals(0L, count(jdbc, "platform_project_plan_executions", "tenant_id", target.organization().id()));
        assertEquals(0L, count(jdbc, "platform_agent_definitions", "tenant_id", target.organization().id()));
        assertEquals(0L, count(jdbc, "platform_model_providers", "tenant_id", target.organization().id()));
        assertEquals(0L, count(jdbc, "platform_governance_policies", "tenant_id", target.organization().id()));
        assertEquals(0L, count(jdbc, "platform_automation_schedules", "tenant_id", target.organization().id()));
        assertEquals(TenantStatus.ACTIVE,
                identities.findTenantById(foreign.organization().id()).orElseThrow().status());
        assertEquals(1L, count(jdbc, "platform_conversations", "tenant_id", foreign.organization().id()));
        assertEquals(1L, count(jdbc, "platform_model_providers", "tenant_id", foreign.organization().id()));
    }

    @Test
    void runtimeQuiesceReleasesFenceAndDefersUntilPreviouslyIssuedLeaseExpires() {
        var jdbc = new JdbcTemplate(dataSource());
        var identities = new PostgresIdentityRepository(jdbc);
        TimeProvider time = Instant::now;
        var cleanup = new OrganizationCleanupApplicationService(
                identities, new PostgresOrganizationCleanupRepository(jdbc), time, 0, 5, 1);
        var organizations = new OrganizationApplicationService(
                identities, new UuidGenerator(), time, cleanup);
        var target = organizations.provisionPersonalOrganization(
                new ProvisionPersonalOrganizationCommand(
                        "cleanup-runtime@example.com", "Runtime", "Runtime Org", "cleanup-runtime"));
        seed(jdbc, target.organization().id(), target.user().id(), "c");
        String runId = "run-cleanup-runtime";
        jdbc.update("""
                INSERT INTO platform_agent_runs
                (id, agent_id, owner_id, conversation_id, project_id, task_id, state,
                 failure_reason, created_at, updated_at, completed_at, tenant_id, revision)
                VALUES (?, ?, ?, ?, NULL, NULL, 'IN_PROGRESS', NULL, ?, ?, NULL, ?, 0)
                """, runId, "agent-cleanup-c", target.user().id(), "conversation-cleanup-c",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), target.organization().id());
        jdbc.update("""
                INSERT INTO platform_run_worker_leases
                (agent_run_id, lease_token, lease_owner, fencing_token, lease_until, revision,
                 acquired_at, heartbeat_at, released_at)
                VALUES (?, gen_random_uuid(), 'runtime-worker', 1,
                        clock_timestamp() + INTERVAL '30 seconds', 1,
                        clock_timestamp(), clock_timestamp(), NULL)
                """, runId);
        var runtime = new PostgresRuntimeCleanupService(jdbc);

        var deferred = runtime.quiesceOrganization(target.organization().id());
        assertFalse(deferred.ready());
        assertTrue(deferred.retryAt().isAfter(Instant.now()));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM platform_agent_runs WHERE id = ?", String.class, runId));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT released_at IS NOT NULL FROM platform_run_worker_leases WHERE agent_run_id = ?",
                Boolean.class, runId)));

        jdbc.update("UPDATE platform_run_worker_leases SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE agent_run_id = ?", runId);
        assertTrue(runtime.quiesceOrganization(target.organization().id()).ready());
        runtime.purgeOrganization(target.organization().id());
        assertEquals(0L, count(jdbc, "platform_agent_runs", "tenant_id", target.organization().id()));
    }

    private static void seed(JdbcTemplate jdbc, String tenant, String user, String suffix) {
        Instant now = Instant.now();
        String agent = "agent-cleanup-" + suffix;
        String project = switch (suffix) {
            case "a" -> "10000000-0000-4000-8000-000000000001";
            case "b" -> "20000000-0000-4000-8000-000000000001";
            default -> "30000000-0000-4000-8000-000000000001";
        };
        jdbc.update("""
                INSERT INTO platform_agent_definitions
                (id, owner_id, tenant_id, name, description,
                 archived_at, revision, created_at, updated_at, status)
                VALUES (?, ?, ?, ?, '', NULL, 1, ?, ?, 'ACTIVE')
                """, agent, user, tenant, "cleanup-agent-" + suffix,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_automation_schedules
                (id, tenant_id, owner_id, agent_id, description, prompt, schedule_type,
                 cron_expression, scheduled_at, timezone, state, next_fire_at, run_count,
                 max_retries, revision, created_at, updated_at)
                VALUES (CAST(? AS UUID), ?, ?, ?, 'cleanup', 'cleanup', 'PERIODIC',
                        '0 * * * * *', NULL, 'UTC', 'ACTIVE', ?, 0, 1, 1, ?, ?)
                """, project, tenant, user, agent, Timestamp.from(now.plusSeconds(60)),
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_model_providers
                (id, tenant_id, owner_id, name, provider_type, base_url, api_key_ciphertext,
                 auth_type, enabled, is_default, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'OPENAI_COMPATIBLE', 'https://example.com',
                        'encrypted', 'BEARER', TRUE, FALSE, ?, ?)
                """, "provider-cleanup-" + suffix, tenant, user, "provider-" + suffix,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_projects
                (id, tenant_id, owner_id, name, description, status, created_at, updated_at)
                VALUES (CAST(? AS UUID), ?, ?, ?, '', 'ACTIVE', ?, ?)
                """, project, tenant, user, "project-" + suffix,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_project_memberships
                (id, project_id, user_id, role, created_at)
                VALUES (gen_random_uuid(), CAST(? AS UUID), ?, 'OWNER', ?)
                """, project, user, Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_conversations
                (id, project_id, task_id, tenant_id, user_id, agent_id, title, status, created_at, updated_at)
                VALUES (?, NULL, NULL, ?, ?, NULL, 'cleanup', 'ACTIVE', ?, ?)
                """, "conversation-cleanup-" + suffix, tenant, user,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_governance_policies
                (tenant_id, updated_by) VALUES (?, ?)
                """, tenant, user);
        jdbc.update("""
                INSERT INTO platform_memory_candidates
                (id, scope_type, scope_id, kind, source_id, source_type, content,
                 confidence, dedupe_key, state, created_at)
                VALUES (?, 'PROJECT', ?, 'FACT', 'cleanup', 'USER', 'cleanup',
                        1.0, ?, 'PENDING_REVIEW', ?)
                """, "memory-project-" + suffix, project, "dedupe-" + suffix, Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_consolidated_memories
                (id, scope_type, scope_id, kind, memory_key, memory_value, created_at, updated_at)
                VALUES (?, 'USER', ?, 'PREFERENCE', ?, 'retain', ?, ?)
                """, "memory-user-" + suffix, user, "retain-" + suffix,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_knowledge_documents
                (id, owner_id, name, content_type, storage_location, status, created_at, updated_at)
                VALUES (?, ?, 'retain', 'text/plain', 'inline:retain', 'READY', ?, ?)
                """, "knowledge-cleanup-" + suffix, user, Timestamp.from(now), Timestamp.from(now));
    }

    private static long count(JdbcTemplate jdbc, String table, String column, String value) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
        return count == null ? 0 : count;
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
