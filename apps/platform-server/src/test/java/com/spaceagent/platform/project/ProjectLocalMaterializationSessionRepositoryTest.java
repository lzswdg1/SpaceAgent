package com.spaceagent.platform.project;

import com.spaceagent.platform.project.domain.ProjectLocalMaterializationSession;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationSessionState;
import com.spaceagent.platform.project.infrastructure.persistence.PostgresProjectLocalMaterializationSessionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectLocalMaterializationSessionRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final String HASH = "sha256:" + "a".repeat(64);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("local_materialization")
            .withUsername("spaceagent")
            .withPassword("spaceagent");

    private DriverManagerDataSource dataSource;

    @BeforeAll
    void migrate() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime").load().migrate();
    }

    @Test
    void persistsScopedSessionRevisionCasExpiryAndRestart() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Scope scope = seedScope(jdbc);
        var repository = new PostgresProjectLocalMaterializationSessionRepository(jdbc);
        var opened = open(scope, "request-1");
        repository.insert(opened);
        assertThat(repository.findByRequest(scope.tenantId(), scope.ownerId(), scope.projectId(), scope.bridgeId(), "request-1"))
                .contains(opened);
        assertThat(repository.findActiveExpiredBefore(NOW.plusSeconds(3600), 10)).contains(opened);

        var uploading = opened.beginUpload(HASH, NOW.plusSeconds(1));
        assertThat(repository.update(uploading, opened.revision(), ProjectLocalMaterializationSessionState.OPEN)).isTrue();
        assertThat(repository.update(uploading, opened.revision(), ProjectLocalMaterializationSessionState.OPEN)).isFalse();
        var restarted = new PostgresProjectLocalMaterializationSessionRepository(new JdbcTemplate(dataSource));
        assertThat(restarted.findById(scope.tenantId(), scope.ownerId(), scope.projectId(), opened.id())).contains(uploading);

        var expired = uploading.expire(NOW.plusSeconds(3600));
        assertThat(restarted.update(expired, uploading.revision(), ProjectLocalMaterializationSessionState.UPLOADING)).isTrue();
        assertThat(restarted.findActiveExpiredBefore(NOW.plusSeconds(7200), 10)).doesNotContain(opened);
    }

    @Test
    void v1059UpgradesIdempotently() {
        String schema = "local_materialization_upgrade";
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server", "classpath:db/platform-runtime").target(MigrationVersion.fromVersion("1058")).load().migrate();
        JdbcTemplate upgraded = new JdbcTemplate(
                com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));
        assertThat(upgraded.queryForObject(
                "SELECT to_regclass('platform_project_local_materialization_sessions') IS NULL", Boolean.class)).isTrue();
        Flyway flyway = Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server", "classpath:db/platform-runtime").load();
        flyway.migrate();
        flyway.migrate();
        assertThat(upgraded.queryForObject(
                "SELECT to_regclass('platform_project_local_materialization_sessions') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(upgraded.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("1098");
    }

    @Test
    void v1060PersistsChunkIdempotencyAcrossRestart() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Scope scope = seedScope(jdbc);
        var sessions = new PostgresProjectLocalMaterializationSessionRepository(jdbc);
        var session = open(scope, "chunk-session-request");
        sessions.insert(session);
        var chunks = new com.spaceagent.platform.project.infrastructure.persistence
                .PostgresProjectLocalMaterializationChunkRepository(jdbc);
        var chunk = com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunk.stored(
                UUID.randomUUID().toString(), session.id(), "chunk-request", "untrusted/../file", 0, 5,
                HASH, "b".repeat(64), NOW);
        assertThat(chunks.insertOrGet(chunk)).isEqualTo(chunk);
        assertThat(chunks.insertOrGet(chunk)).isEqualTo(chunk);
        assertThat(new com.spaceagent.platform.project.infrastructure.persistence
                .PostgresProjectLocalMaterializationChunkRepository(new JdbcTemplate(dataSource))
                .findBySessionId(session.id())).containsExactly(chunk);
        assertThatThrownBy(() -> chunks.insertOrGet(
                com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunk.stored(
                        UUID.randomUUID().toString(), session.id(), "chunk-request", "other", 0, 5,
                        HASH, "c".repeat(64), NOW))).isInstanceOf(IllegalStateException.class);

        String schema = "local_materialization_chunk_upgrade";
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server", "classpath:db/platform-runtime").target(MigrationVersion.fromVersion("1059")).load().migrate();
        JdbcTemplate upgraded = new JdbcTemplate(
                com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));
        assertThat(upgraded.queryForObject(
                "SELECT to_regclass('platform_project_local_materialization_chunks') IS NULL", Boolean.class)).isTrue();
        Flyway flyway = Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server", "classpath:db/platform-runtime").load();
        flyway.migrate();
        flyway.migrate();
        assertThat(upgraded.queryForObject(
                "SELECT to_regclass('platform_project_local_materialization_chunks') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(upgraded.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("1098");
    }

    @Test
    void v1062AddsManagedSnapshotEvidenceIdempotently() {
        String schema="managed_snapshot_upgrade";
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").target(MigrationVersion.fromVersion("1060")).load().migrate();
        JdbcTemplate upgraded = new JdbcTemplate(
                com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));
        assertThat(upgraded.queryForObject("SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='platform_source_repositories' AND column_name='snapshot_ref')",Boolean.class)).isFalse();
        Flyway flyway=Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema)).locations("classpath:db/platform-server","classpath:db/platform-runtime").load();flyway.migrate();flyway.migrate();
        assertThat(upgraded.queryForObject("SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='platform_source_repositories' AND column_name='snapshot_ref')",Boolean.class)).isTrue();
        assertThat(upgraded.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class)).isEqualTo("1098");
    }

    private static ProjectLocalMaterializationSession open(Scope scope, String requestId) {
        return ProjectLocalMaterializationSession.open(
                UUID.randomUUID().toString(), scope.tenantId(), scope.ownerId(), scope.projectId(), scope.bridgeId(),
                "device-1", "root_12345678", requestId, NOW.plusSeconds(3600), NOW);
    }

    private static Scope seedScope(JdbcTemplate jdbc) {
        String tenantId = UUID.randomUUID().toString();
        String ownerId = UUID.randomUUID().toString();
        String projectId = UUID.randomUUID().toString();
        String bridgeId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",
                tenantId, "Materialization Tenant", "materialization-" + tenantId, now, now);
        jdbc.update("INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at) VALUES(?,?,?,'Owner',?,?)",
                ownerId, tenantId, ownerId + "@example.com", now, now);
        jdbc.update("INSERT INTO platform_tenant_memberships(tenant_id,user_id,tenant_role,status,joined_at,updated_at) VALUES(?,?,'OWNER','ACTIVE',?,?)",
                tenantId, ownerId, now, now);
        jdbc.update("INSERT INTO platform_projects(id,tenant_id,owner_id,name,status,created_at,updated_at) VALUES(CAST(? AS UUID),?,?,?,'ACTIVE',?,?)",
                projectId, tenantId, ownerId, "Materialization Project", now, now);
        jdbc.update("""
                INSERT INTO platform_local_workspace_bridges(
                    id,tenant_id,owner_id,display_name,device_id,root_handle,token_hash,token_prefix,state,
                    last_seen_at,created_at,updated_at,revoked_at)
                VALUES(CAST(? AS UUID),?,?,?,'device-1','root_12345678',?,'brg_fixture','ACTIVE',?,?,?,NULL)
                """, bridgeId, tenantId, ownerId, "Fixture Bridge", bridgeId.replace("-", "").repeat(2), now, now, now);
        return new Scope(tenantId, ownerId, projectId, bridgeId);
    }

    private record Scope(String tenantId, String ownerId, String projectId, String bridgeId) { }
}
