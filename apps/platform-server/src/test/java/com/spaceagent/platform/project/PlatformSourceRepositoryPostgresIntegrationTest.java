package com.spaceagent.platform.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.application.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.platform.project.infrastructure.persistence.*;
import com.spaceagent.shared.id.UuidGenerator;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PlatformSourceRepositoryPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-22T23:40:00Z");
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("spaceagent_source_repository")
            .withUsername("spaceagent").withPassword("spaceagent");

    @BeforeAll
    static void migrateAndSeed() {
        Flyway.configure().dataSource(dataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.update("""
                INSERT INTO platform_tenants (id,name,slug,status,created_at,updated_at)
                VALUES ('tenant-1','Tenant','tenant','ACTIVE',?,?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_users (id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES ('owner-1','tenant-1','owner@example.com','Owner',?,?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_tenant_memberships
                    (tenant_id,user_id,tenant_role,status,joined_at,updated_at)
                VALUES ('tenant-1','owner-1','OWNER','ACTIVE',?,?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    @Test
    void persistsMcpProvenanceOpaqueBridgeAndSourceMetadata() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ObjectMapper mapper = new ObjectMapper();
        UuidGenerator ids = new UuidGenerator();
        IdentityOwnershipPort identity = (tenant, user) -> true;
        var projectRepository = new PostgresProjectRepository(jdbc);
        var memberships = new PostgresProjectMembershipRepository(jdbc);
        ProjectAccessPolicy access = new ProjectAccessPolicy(projectRepository, memberships, identity, (t, u, w) -> {});
        ProjectApplicationService projects = new ProjectApplicationService(
                projectRepository, memberships, access, ids, () -> NOW);
        String projectId = projects.createProject(new CreateProjectCommand(
                "tenant-1", "owner-1", "Persistent Source", null)).id();

        var bridgeRepository = new PostgresLocalWorkspaceBridgeRepository(jdbc);
        LocalWorkspaceBridgeApplicationService bridges = new LocalWorkspaceBridgeApplicationService(
                bridgeRepository, access, ids, () -> NOW);
        SourceRepositoryApplicationService sources = new SourceRepositoryApplicationService(
                new PostgresSourceRepositoryRepository(jdbc), access, bridges, ids, () -> NOW);
        var createdBridge = bridges.register(new RegisterLocalWorkspaceBridgeCommand(
                "tenant-1", "owner-1", "Laptop", "device-1", "root_abcdefgh"));
        var localSource = sources.importLocal(new ImportLocalRepositoryCommand(
                "tenant-1", "owner-1", projectId, createdBridge.bridge().id(),
                "root_abcdefgh", "Local", "main"));
        String mcpConnection = "00000000-0000-4000-8000-000000000099";
        String mcpInvocation = "00000000-0000-4000-8000-000000000098";
        var mcpSource = sources.importGithubMcp(new ImportGithubMcpRepositoryCommand(
                "tenant-1", "owner-1", projectId, mcpConnection, mcpInvocation, "mcp-42",
                "openai", "openai-java", "https://github.com/openai/openai-java.git",
                "main", false, false));

        var taskRepository = new PostgresTaskRepository(jdbc, mapper);
        TaskApplicationService tasks = new TaskApplicationService(
                taskRepository, access, ids, () -> NOW);
        String taskId = tasks.createTask(new CreateTaskCommand(
                "tenant-1", "owner-1", projectId, null, "Implement", "goal",
                null, List.of(), List.of())).id();
        var workspaceRepository = new PostgresWorkspaceRepository(jdbc);
        var directoryRepository = new PostgresProjectDirectoryRepository(jdbc);
        var directoryApi = new ProjectDirectoryApplicationService(
                directoryRepository, new PostgresSourceRepositoryRepository(jdbc), access,
                ids, () -> NOW);
        WorkspaceApplicationService workspaces = new WorkspaceApplicationService(
                workspaceRepository,
                new PostgresBridgeWorkspaceCommandRepository(jdbc),
                access,
                taskRepository,
                new PostgresSourceRepositoryRepository(jdbc),
                bridgeRepository,
                bridges,
                request -> credential("Basic mcp-checkout-token"),
                new WorkspaceProvisioningGateway() {
                    @Override
                    public ProvisionedWorkspace provision(
                            Workspace workspace, SourceRepository source, String authorizationHeader) {
                        return new ProvisionedWorkspace(
                                "managed:" + workspace.id(), "b".repeat(40));
                    }

                    @Override
                    public void cleanup(Workspace workspace, SourceRepository source) {}
                },
                ids,
                () -> NOW,
                directoryApi);
        var primaryWorkspace = workspaces.provision(new WorkspaceApplicationApi.ProvisionCommand(
                "tenant-1", "owner-1", projectId, taskId, mcpSource.id(), "main"));
        var childWorkspace = workspaces.provision(new WorkspaceApplicationApi.ProvisionCommand(
                "tenant-1", "owner-1", projectId, taskId, mcpSource.id(), "main",
                "delegation:postgres-test"));
        Workspace stale = new Workspace(
                ids.nextId(), "tenant-1", projectId, primaryWorkspace.projectDirectoryId(),
                taskId, mcpSource.id(), null,
                "recovery", WorkspaceMode.MANAGED_GIT, UUID.randomUUID().toString(),
                "main", "spaceagent/recovery/postgres", null, null, true,
                WorkspaceState.PROVISIONING, null, 0, "owner-1",
                NOW.minusSeconds(600), NOW.minusSeconds(600));
        workspaceRepository.save(stale);
        assertThat(workspaceRepository.findStaleProvisioning(
                NOW.minusSeconds(300), 10)).extracting(Workspace::id).contains(stale.id());
        assertThat(workspaceRepository.failProvisioning(
                stale.id(), stale.revision(), "WORKSPACE_PROVISIONING_INTERRUPTED", NOW)).isTrue();
        assertThat(workspaceRepository.findById(stale.id()).orElseThrow().state())
                .isEqualTo(WorkspaceState.FAILED);

        var reconnectedSources = new PostgresSourceRepositoryRepository(new JdbcTemplate(dataSource()));
        assertThat(reconnectedSources.findByProjectId(projectId))
                .extracting(SourceRepository::id)
                .containsExactlyInAnyOrder(localSource.id(), mcpSource.id());
        assertThat(reconnectedSources.findById(mcpSource.id()).orElseThrow().mcpConnectionId())
                .isEqualTo(mcpConnection);
        assertThat(reconnectedSources.findById(mcpSource.id()).orElseThrow().mcpInvocationId())
                .isEqualTo(mcpInvocation);
        assertThat(primaryWorkspace.id()).isNotEqualTo(childWorkspace.id());
        assertThat(childWorkspace.isolationKey()).isEqualTo("delegation:postgres-test");
        assertThat(jdbc.queryForObject("""
                SELECT token_hash FROM platform_local_workspace_bridges
                WHERE id=CAST(? AS UUID)
                """, String.class, createdBridge.bridge().id()))
                .hasSize(64).isNotEqualTo(createdBridge.bridgeToken());
        assertThat(columnExists(jdbc, "platform_source_repositories", "local_path")).isFalse();
        assertThat(columnExists(jdbc, "platform_source_repositories", "mcp_connection_id")).isTrue();
        assertThat(columnExists(jdbc, "platform_source_repositories", "mcp_invocation_id")).isTrue();
        assertThat(columnExists(jdbc, "platform_local_workspace_bridges", "root_handle")).isTrue();
        assertThat(columnExists(jdbc, "platform_workspaces", "worktree_key")).isTrue();
        assertThat(columnExists(jdbc, "platform_project_blueprints", "document_json")).isTrue();
        assertThat(columnExists(jdbc, "platform_artifacts", "content_hash")).isTrue();
        assertThat(columnExists(jdbc, "platform_workspaces", "isolation_key")).isTrue();
        assertThat(columnExists(jdbc, "platform_agent_delegations", "workspace_id")).isTrue();
        assertThat(columnExists(jdbc, "platform_run_worker_leases", "fencing_token")).isTrue();
        assertThat(columnExists(jdbc, "platform_runtime_continuations", "deduplication_key")).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_observability_runs') IS NOT NULL", Boolean.class))
                .isTrue();

        var mergeRepository = new PostgresSourceMergeRepository(jdbc);
        SourceMerge preparing = new SourceMerge(
                ids.nextId(), "tenant-1", projectId, taskId, mcpSource.id(),
                primaryWorkspace.id(), "run-opaque",
                "00000000-0000-4000-8000-000000000091",
                "00000000-0000-4000-8000-000000000092",
                "refs/heads/main", primaryWorkspace.headCommit(),
                "sha256:" + "a".repeat(64), "reviewed commit",
                "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64),
                SourceMergeState.PREPARING, null, null, null, null, 0,
                "owner-1", NOW, NOW, null, null);
        mergeRepository.insert(preparing);
        SourceMerge readyMerge = preparing.transition(
                SourceMergeState.READY, "d".repeat(40), null, null, null, NOW.plusSeconds(1));
        assertThat(mergeRepository.update(
                readyMerge, preparing.revision(), SourceMergeState.PREPARING)).isTrue();
        assertThat(new PostgresSourceMergeRepository(new JdbcTemplate(dataSource()))
                .findById(preparing.id()).orElseThrow().state()).isEqualTo(SourceMergeState.READY);
        assertThat(columnExists(jdbc, "platform_source_merge_jobs", "idempotency_hash")).isTrue();
        assertThat(jdbc.queryForObject("SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class))
                .isEqualTo(110);
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Boolean value = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM information_schema.columns
                 WHERE table_name=? AND column_name=?)
                """, Boolean.class, table, column);
        return Boolean.TRUE.equals(value);
    }
    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private static WorkspaceCheckoutCredentialPort.CredentialLease credential(String header) {
        return new WorkspaceCheckoutCredentialPort.CredentialLease() {
            @Override public String authorizationHeader() { return header; }
            @Override public void close() { }
        };
    }
}
