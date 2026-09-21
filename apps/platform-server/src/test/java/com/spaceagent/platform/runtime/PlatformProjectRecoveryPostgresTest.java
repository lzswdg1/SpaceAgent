package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.ProjectRecoveryApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectExecutionContextSnapshot;
import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresProjectExecutionContextSnapshotRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PlatformProjectRecoveryPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("project_recovery")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
        seed(new JdbcTemplate(dataSource()));
    }

    @Test
    void persistsImmutableIdempotentSnapshotAndRejectsRunScopeReplacement() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        var repository = new PostgresProjectExecutionContextSnapshotRepository(jdbc);
        ProjectExecutionContextSnapshot snapshot = snapshot(SNAPSHOT, DIRECTORY, "a");

        repository.save(snapshot);

        ProjectExecutionContextSnapshot reloaded =
                new PostgresProjectExecutionContextSnapshotRepository(jdbc)
                        .findLatestByRunId(RUN).orElseThrow();
        assertThat(reloaded.id()).isEqualTo(snapshot.id());
        assertThat(reloaded.snapshotHash()).isEqualTo(snapshot.snapshotHash());
        assertThat(reloaded.payloadJson()).isEqualTo(snapshot.payloadJson());
        assertThat(read(reloaded.payloadJson()).nextAction()).isEqualTo("RESUME_PLAN_STEP");
        assertThat(repository.findByIdempotencyHash(TENANT, USER, hash("a")))
                .map(ProjectExecutionContextSnapshot::id).contains(snapshot.id());
        assertThatThrownBy(() -> repository.save(snapshot(uuid(91), DIRECTORY, "a")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.save(snapshot(uuid(92), DEFAULT_DIRECTORY, "b")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.save(withSnapshotHash(
                        snapshot(uuid(93), DIRECTORY, "b"), hash("e"))))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM platform_agent_runs WHERE id = ?", RUN);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_project_execution_context_snapshots", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(110L);
    }

    @Test
    void v1044UpgradesV1043WithoutInventingRecoverySnapshots() {
        String schema = "project_recovery_upgrade";
        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1043")).load().migrate();

        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1044")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(schemaDataSource(schema));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_project_execution_context_snapshots", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("1044");
    }

    private static void seed(JdbcTemplate jdbc) {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) "
                + "VALUES(?, 'Recovery', 'recovery', 'ACTIVE', ?, ?)", TENANT, now, now);
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) "
                + "VALUES(?, ?, 'recovery@example.com', 'Recovery', ?, ?)", USER, TENANT, now, now);
        jdbc.update("INSERT INTO platform_tenant_memberships(tenant_id,user_id,tenant_role,status,joined_at,updated_at) "
                + "VALUES(?, ?, 'OWNER', 'ACTIVE', ?, ?)", TENANT, USER, now, now);
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,description,created_at,updated_at,status,revision)
                VALUES(?,?,?,'Recovery Agent',NULL,?,?,'ACTIVE',1)
                """, AGENT, USER, TENANT, now, now);
        jdbc.update("""
                INSERT INTO platform_projects(id,tenant_id,owner_id,name,status,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,?,'Recovery Project','ACTIVE',?,?)
                """, PROJECT, TENANT, USER, now, now);
        jdbc.update("INSERT INTO platform_project_memberships(id,project_id,user_id,role,created_at) "
                + "VALUES(gen_random_uuid(),CAST(? AS UUID),?,'OWNER',?)", PROJECT, USER, now);
        jdbc.update("""
                INSERT INTO platform_tasks(
                    id,project_id,tenant_id,owner_user_id,parent_task_id,title,goal,constraints_json,
                    acceptance_criteria_json,state,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),?,?,NULL,'Root','Root goal','[]','[]',
                    'IN_PROGRESS',?,?)
                """, ROOT_TASK, PROJECT, TENANT, USER, now, now);
        jdbc.update("""
                INSERT INTO platform_tasks(
                    id,project_id,tenant_id,owner_user_id,parent_task_id,title,goal,constraints_json,
                    acceptance_criteria_json,state,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),?,?,CAST(? AS UUID),'Child','Child goal',
                    '[]','[\"tests pass\"]','IN_PROGRESS',?,?)
                """, TASK, PROJECT, TENANT, USER, ROOT_TASK, now, now);
        jdbc.update("""
                INSERT INTO platform_task_plans(
                    id,project_id,root_task_id,version_number,status,created_by,approved_by,
                    approved_at,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),1,'ACTIVE',?,?,?, ?,?)
                """, PLAN, PROJECT, ROOT_TASK, USER, USER, now, now, now);
        jdbc.update("UPDATE platform_tasks SET current_task_plan_id=CAST(? AS UUID) "
                + "WHERE id=CAST(? AS UUID)", PLAN, ROOT_TASK);
        jdbc.update("""
                INSERT INTO platform_plan_steps(
                    id,task_plan_id,project_id,step_key,sequence_number,child_task_id,
                    expected_output,acceptance_criteria_json,approval_required,state,
                    created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),'implement',0,
                    CAST(? AS UUID),'code','[\"tests pass\"]',FALSE,'IN_PROGRESS',?,?)
                """, STEP, PLAN, PROJECT, TASK, now, now);
        jdbc.update("""
                INSERT INTO platform_source_repositories(
                    id,project_id,tenant_id,provider_repository_id,display_name,remote_url,
                    default_branch,repository_type,state,visibility,created_by,created_at,updated_at)
                VALUES(CAST(? AS UUID),CAST(? AS UUID),?,'recovery-source','Recovery Source',
                    'https://example.com/recovery.git','main','GENERIC','READY','PRIVATE',?,?,?)
                """, SOURCE, PROJECT, TENANT, USER, now, now);
        jdbc.update("""
                INSERT INTO platform_project_directories(
                    id,tenant_id,project_id,source_repository_id,name,relative_path,
                    is_default,state,created_by,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,CAST(? AS UUID),NULL,'Recovery Project','.',TRUE,
                    'ACTIVE',?,?,?)
                """, DEFAULT_DIRECTORY, TENANT, PROJECT, USER, now, now);
        jdbc.update("""
                INSERT INTO platform_project_directories(
                    id,tenant_id,project_id,source_repository_id,name,relative_path,
                    is_default,state,created_by,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,CAST(? AS UUID),CAST(? AS UUID),'Recovery Source','.',FALSE,
                    'ACTIVE',?,?,?)
                """, DIRECTORY, TENANT, PROJECT, SOURCE, USER, now, now);
        jdbc.update("""
                INSERT INTO platform_project_blueprints(
                    id,tenant_id,project_id,version_number,status,source,source_repository_id,
                    created_by,confirmed_by,confirmed_at,document_json,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,CAST(? AS UUID),2,'CONFIRMED','USER',CAST(? AS UUID),
                    ?,?,?, '{\"goal\":\"Recovery\"}',?,?)
                """, BLUEPRINT, TENANT, PROJECT, SOURCE, USER, USER, now, now, now);
        jdbc.update("""
                INSERT INTO platform_workspaces(
                    id,tenant_id,project_id,project_directory_id,task_id,source_repository_id,
                    isolation_key,mode,worktree_key,base_ref,branch_name,worktree_ref,head_commit,
                    writable,state,revision,created_by,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                    CAST(? AS UUID),'primary','MANAGED_GIT',gen_random_uuid(),'main',
                    'spaceagent/recovery','managed:recovery',?,TRUE,'READY',1,?,?,?)
                """, WORKSPACE, TENANT, PROJECT, DIRECTORY, TASK, SOURCE, "a".repeat(40),
                USER, now, now);
        jdbc.update("""
                INSERT INTO platform_conversations(
                    id,project_id,tenant_id,user_id,agent_id,title,status,created_at,updated_at,
                    project_uuid,project_directory_id,active_task_id)
                VALUES(?,?,?,?,?,'Recovery','ACTIVE',?,?,CAST(? AS UUID),CAST(? AS UUID),
                    CAST(? AS UUID))
                """, CONVERSATION, PROJECT, TENANT, USER, AGENT, now, now, PROJECT, DIRECTORY,
                ROOT_TASK);
        jdbc.update("""
                INSERT INTO platform_agent_runs(
                    id,agent_id,tenant_id,owner_id,conversation_id,
                    project_id,task_id,project_uuid,project_directory_id,workspace_id,task_uuid,
                    task_plan_id,plan_step_id,execution_cursor,revision,state,created_at,updated_at)
                VALUES(?,?,?,?,?, ?,?,CAST(? AS UUID),CAST(? AS UUID),
                    CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                    '{\"phase\":\"coding\",\"stepId\":null,\"checkpointId\":null,\"checkpointSequence\":0}',
                    1,'IN_PROGRESS',?,?)
                """, RUN, AGENT, TENANT, USER, CONVERSATION, PROJECT, TASK,
                PROJECT, DIRECTORY, WORKSPACE, TASK, PLAN, STEP, now, now);
        jdbc.update("""
                INSERT INTO platform_agent_run_configuration_snapshots(
                    id,run_id,tenant_id,owner_id,agent_id,snapshot_state,captured_at)
                VALUES(?,?,?,?,?,'LEGACY_UNSNAPSHOTTED',?)
                """, RUN, RUN, TENANT, USER, AGENT, now);
        jdbc.update("""
                INSERT INTO platform_conversation_context_snapshots(
                    id,conversation_id,version,summary,from_message_sequence,to_message_sequence,
                    token_count,checksum,created_at)
                VALUES(?,?,3,'context',0,2,10,'sha256:context',?)
                """, CONTEXT_SNAPSHOT, CONVERSATION, now);
        jdbc.update("""
                INSERT INTO platform_run_checkpoints(
                    id,agent_run_id,sequence,state_snapshot,created_at)
                VALUES(?,?,1,'{\"phase\":\"coding\"}',?)
                """, CHECKPOINT, RUN, now);
    }

    private static ProjectExecutionContextSnapshot snapshot(
            String id, String directoryId, String hashSuffix) {
        String payload = write(new ProjectRecoveryApplicationApi.RecoveryContext(
                null, null, null, null, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "RESUME_PLAN_STEP", List.of()));
        return new ProjectExecutionContextSnapshot(
                id, TENANT, USER, PROJECT, directoryId, CONVERSATION, TASK, PLAN, STEP,
                RUN, RUN, WORKSPACE, BLUEPRINT, 2,
                CONTEXT_SNAPSHOT, 3, CHECKPOINT,
                hash(hashSuffix), hash("c"), sha256(payload), payload, NOW);
    }

    private static String hash(String suffix) {
        char value = suffix.charAt(0);
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static ProjectExecutionContextSnapshot withSnapshotHash(
            ProjectExecutionContextSnapshot value, String snapshotHash) {
        return new ProjectExecutionContextSnapshot(
                value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.projectDirectoryId(), value.conversationId(), value.taskId(),
                value.taskPlanId(), value.planStepId(), value.agentRunId(),
                value.runConfigurationSnapshotId(), value.workspaceId(), value.blueprintId(),
                value.blueprintVersion(), value.conversationContextSnapshotId(),
                value.conversationContextSnapshotVersion(), value.checkpointId(),
                value.idempotencyHash(), value.inputHash(), snapshotHash,
                value.payloadJson(), value.createdAt());
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String write(ProjectRecoveryApplicationApi.RecoveryContext value) {
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static ProjectRecoveryApplicationApi.RecoveryContext read(String value) {
        try {
            return new ObjectMapper().findAndRegisterModules()
                    .readValue(value, ProjectRecoveryApplicationApi.RecoveryContext.class);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DriverManagerDataSource schemaDataSource(String schema) {
        return com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema);
    }

    private static String uuid(int value) {
        return "00000000-0000-4000-8000-" + String.format("%012d", value);
    }

    private static final Instant NOW = Instant.parse("2026-09-05T03:00:00Z");
    private static final String TENANT = uuid(1);
    private static final String USER = uuid(2);
    private static final String AGENT = uuid(3);
    private static final String AGENT_VERSION = uuid(4);
    private static final String PROJECT = uuid(5);
    private static final String ROOT_TASK = uuid(6);
    private static final String TASK = uuid(7);
    private static final String PLAN = uuid(8);
    private static final String STEP = uuid(9);
    private static final String SOURCE = uuid(10);
    private static final String DEFAULT_DIRECTORY = uuid(11);
    private static final String DIRECTORY = uuid(12);
    private static final String WORKSPACE = uuid(13);
    private static final String CONVERSATION = uuid(14);
    private static final String RUN = uuid(15);
    private static final String CHECKPOINT = uuid(16);
    private static final String SNAPSHOT = uuid(17);
    private static final String BLUEPRINT = uuid(18);
    private static final String CONTEXT_SNAPSHOT = uuid(19);
}
