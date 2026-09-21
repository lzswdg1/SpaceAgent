package com.spaceagent.platform.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.AddProjectMemberCommand;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.api.CreateChatRootTaskCommand;
import com.spaceagent.platform.project.api.GetChatTaskQuery;
import com.spaceagent.platform.project.api.TransitionChatTaskCommand;
import com.spaceagent.platform.project.api.TaskTransition;
import com.spaceagent.platform.project.api.TransitionTaskCommand;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.TaskApplicationService;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectOwnershipAdapter;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectRepository;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresTaskRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class PlatformTaskPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T11:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_task_foundation")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrateAndSeedIdentity() {
        Flyway.configure()
                .dataSource(newDataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        seedTenant(jdbc, "tenant-a", "Tenant A");
        seedUser(jdbc, "owner-a", "tenant-a");
        seedUser(jdbc, "member-a", "tenant-a");
    }

    @Test
    void migrationPersistsTaskIntentParentAndMemoryAncestry() {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        PostgresProjectRepository projects = new PostgresProjectRepository(jdbc);
        PostgresProjectMembershipRepository memberships =
                new PostgresProjectMembershipRepository(jdbc);
        PostgresTaskRepository tasks = new PostgresTaskRepository(jdbc, new ObjectMapper());
        IdentityOwnershipPort identityOwnership = (tenantId, principalId) -> Boolean.TRUE.equals(
                jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM platform_tenant_memberships
                            WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                        )
                        """, Boolean.class, tenantId, principalId));
        ProjectAccessPolicy accessPolicy = new ProjectAccessPolicy(
                projects, memberships, identityOwnership, (t, u, w) -> {});
        UuidGenerator ids = new UuidGenerator();
        TimeProvider time = () -> NOW;
        ProjectApplicationService projectService = new ProjectApplicationService(
                projects, memberships, accessPolicy, ids, time);
        TaskApplicationService taskService = new TaskApplicationService(
                tasks, accessPolicy, ids, time);

        String projectId = projectService.createProject(new CreateProjectCommand(
                "tenant-a", "owner-a", "Persistent Task Project", null)).id();
        projectService.addMember(new AddProjectMemberCommand(
                "tenant-a", "owner-a", projectId, "member-a", ProjectRole.MEMBER));
        var parent = taskService.createTask(new CreateTaskCommand(
                "tenant-a", "owner-a", projectId, null, "Parent", "Parent goal", null,
                List.of("no network"), List.of("parent passes")));
        var child = taskService.createTask(new CreateTaskCommand(
                "tenant-a", "owner-a", projectId, parent.id(), "Child", "Child goal",
                "Durable child", List.of("no writes"), List.of("child passes")));

        PostgresTaskRepository reconnected = new PostgresTaskRepository(
                new JdbcTemplate(newDataSource()), new ObjectMapper());
        var reloaded = reconnected.findById(child.id()).orElseThrow();
        assertEquals(projectId, reloaded.projectId());
        assertEquals(parent.id(), reloaded.parentTaskId());
        assertEquals(List.of("no writes"), reloaded.constraints());
        assertEquals(List.of("child passes"), reloaded.acceptanceCriteria());

        taskService.transitionTask(new TransitionTaskCommand(
                "tenant-a", "owner-a", projectId, child.id(), TaskTransition.MARK_READY));
        assertEquals(TaskState.IN_PROGRESS, taskService.transitionTask(new TransitionTaskCommand(
                "tenant-a", "member-a", projectId, child.id(), TaskTransition.START)).state());

        PostgresProjectOwnershipAdapter ownership = new PostgresProjectOwnershipAdapter(jdbc);
        assertEquals(projectId, ownership.findProjectIdByTask(child.id()).orElseThrow());
        assertEquals("uuid", columnType(jdbc, "platform_tasks", "id"));
        assertEquals("uuid", columnType(jdbc, "platform_tasks", "project_id"));
        assertNotNull(jdbc.queryForObject("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = current_schema()
                  AND table_name = 'platform_tasks'
                  AND constraint_name = 'fk_platform_tasks_parent_same_project'
                """, String.class));

        String otherProject = projectService.createProject(new CreateProjectCommand(
                "tenant-a", "owner-a", "Other Persistent Project", null)).id();
        BusinessException crossProjectParent = assertThrows(BusinessException.class,
                () -> taskService.createTask(new CreateTaskCommand(
                        "tenant-a", "owner-a", otherProject, parent.id(),
                        "Invalid", "Invalid parent", null, List.of(), List.of())));
        assertEquals("TASK_NOT_FOUND", crossProjectParent.getCode());
    }

    @Test
    void chatRootTaskIsMessageIdempotentOwnerScopedAndTerminal() {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        String conversationId = "00000000-0000-4000-8000-000000000081";
        String messageId = "00000000-0000-4000-8000-000000000082";
        jdbc.update("""
                INSERT INTO platform_conversations(
                    id,tenant_id,user_id,title,status,created_at,updated_at)
                VALUES(?,?,?,'Chat task','ACTIVE',?,?)
                """, conversationId, "tenant-a", "owner-a",
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_messages(
                    id,conversation_id,sequence_number,role,content,created_at)
                VALUES(?,?,0,'USER','Create a durable goal',?)
                """, messageId, conversationId, Timestamp.from(NOW));
        PostgresTaskRepository tasks = new PostgresTaskRepository(jdbc, new ObjectMapper());
        TaskApplicationService service = new TaskApplicationService(
                tasks, new ProjectAccessPolicy(
                        new PostgresProjectRepository(jdbc),
                        new PostgresProjectMembershipRepository(jdbc),
                        (tenantId, principalId) -> true, (t, u, w) -> {}),
                new UuidGenerator(), () -> NOW);
        CreateChatRootTaskCommand command = new CreateChatRootTaskCommand(
                "tenant-a", "owner-a", conversationId, messageId,
                "Create a durable goal", "Create a durable goal");

        var created = service.createOrGetChatRootTask(command);
        var replay = service.createOrGetChatRootTask(command);

        assertEquals(created.id(), replay.id());
        assertEquals(null, created.projectId());
        assertEquals(TaskState.IN_PROGRESS, created.state());
        assertEquals(created.id(), service.getChatTask(new GetChatTaskQuery(
                "tenant-a", "owner-a", conversationId, created.id())).id());
        assertEquals(TaskState.COMPLETED, service.transitionChatTask(
                new TransitionChatTaskCommand(
                        "tenant-a", "owner-a", conversationId, created.id(),
                        TaskTransition.COMPLETE)).state());
        assertThrows(BusinessException.class, () -> service.getChatTask(new GetChatTaskQuery(
                "tenant-a", "member-a", conversationId, created.id())));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM platform_tasks WHERE source_message_id=?",
                Long.class, messageId));
        assertEquals(110L, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class));
    }

    @Test
    void v1049BackfillsExistingProjectTasksWithoutChangingTheirScope() {
        String schema = "chat_task_upgrade";
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1048")).load().migrate();
        DriverManagerDataSource scoped =
                com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema);
        JdbcTemplate jdbc = new JdbcTemplate(scoped);
        seedTenant(jdbc, "upgrade-tenant", "Upgrade Tenant");
        seedUser(jdbc, "upgrade-owner", "upgrade-tenant");
        String projectId = "00000000-0000-4000-8000-0000000000a1";
        String taskId = "00000000-0000-4000-8000-0000000000a2";
        jdbc.update("""
                INSERT INTO platform_projects(
                    id,tenant_id,owner_id,name,status,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,?,?,'ACTIVE',?,?)
                """, projectId, "upgrade-tenant", "upgrade-owner", "Upgrade Project",
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_project_memberships(id,project_id,user_id,role,created_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),?,'OWNER',?)
                """, "00000000-0000-4000-8000-0000000000a3", projectId,
                "upgrade-owner", Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_tasks(
                    id,project_id,title,goal,state,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),'Existing','Existing goal','PENDING',?,?)
                """, taskId, projectId, Timestamp.from(NOW), Timestamp.from(NOW));

        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime").load().migrate();

        assertEquals("1098", jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class));
        assertEquals("upgrade-tenant", jdbc.queryForObject(
                "SELECT tenant_id FROM platform_tasks WHERE id=CAST(? AS UUID)",
                String.class, taskId));
        assertEquals("upgrade-owner", jdbc.queryForObject(
                "SELECT owner_user_id FROM platform_tasks WHERE id=CAST(? AS UUID)",
                String.class, taskId));
        assertEquals(projectId, jdbc.queryForObject(
                "SELECT project_id::text FROM platform_tasks WHERE id=CAST(? AS UUID)",
                String.class, taskId));
    }

    private static String columnType(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private static void seedTenant(JdbcTemplate jdbc, String tenantId, String name) {
        jdbc.update("""
                INSERT INTO platform_tenants (id, name, slug, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, tenantId, name, tenantId, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static void seedUser(JdbcTemplate jdbc, String userId, String tenantId) {
        jdbc.update("""
                INSERT INTO platform_users (
                    id, tenant_id, external_id, display_name, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, userId, tenantId, userId + "@example.com", userId,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_tenant_memberships (
                    tenant_id, user_id, tenant_role, status, joined_at, updated_at
                ) VALUES (?, ?, 'MEMBER', 'ACTIVE', ?, ?)
                """, tenantId, userId, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
