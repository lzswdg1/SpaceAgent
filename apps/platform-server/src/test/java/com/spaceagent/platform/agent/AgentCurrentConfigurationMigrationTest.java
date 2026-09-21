package com.spaceagent.platform.agent;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AgentCurrentConfigurationMigrationTest {

    private static final Instant NOW = Instant.parse("2026-09-11T07:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("agent_current_configuration")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @Test
    void freshSchemaContainsExpandPhaseAuthorities() {
        clean();
        JdbcTemplate jdbc = migrate(null);

        assertThat(table(jdbc, "platform_agent_current_configurations")).isTrue();
        assertThat(table(jdbc, "platform_agent_mcp_bindings")).isTrue();
        assertThat(table(jdbc, "platform_agent_run_configuration_snapshots")).isTrue();
        assertThat(table(jdbc, "platform_agent_run_mcp_binding_snapshots")).isTrue();
        assertThat(table(jdbc, "platform_agent_versions")).isFalse();
        assertThat(table(jdbc, "platform_agent_version_mcp_bindings")).isFalse();
        assertThat(table(jdbc, "platform_agent_version_reviews")).isFalse();
        assertThat(table(jdbc, "platform_agent_version_activation_schedules")).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema() AND (
                    (table_name='platform_project_blueprints'
                        AND column_name IN ('generated_by_agent_id',
                            'generated_by_run_configuration_snapshot_id'))
                    OR (table_name='platform_task_plans'
                        AND column_name IN ('generated_by_agent_id',
                            'generated_by_run_configuration_snapshot_id'))
                    OR (table_name='platform_project_coding_jobs'
                        AND column_name='reviewer_agent_id'))
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=current_schema()
                  AND (column_name LIKE '%agent_version_id'
                    OR column_name='current_agent_version_id')
                """, Integer.class)).isZero();
        assertThat(latestVersion(jdbc)).isEqualTo("1098");
    }

    @Test
    void v1078UpgradeUsesLatestSavedAgentConfigAndPreservesHistoricalRunConfig() {
        clean();
        JdbcTemplate jdbc = migrate(MigrationVersion.fromVersion("1078"));
        String tenantId = UUID.randomUUID().toString();
        String ownerId = UUID.randomUUID().toString();
        String publishedId = UUID.randomUUID().toString();
        String draftId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(NOW);

        jdbc.update("""
                INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at)
                VALUES(?,?,?,'ACTIVE',?,?)
                """, tenantId, "Personal", "personal-" + tenantId, now, now);
        jdbc.update("""
                INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES(?,?,?,'Owner',?,?)
                """, ownerId, tenantId, ownerId + "@example.com", now, now);
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,status,revision,created_at,updated_at)
                VALUES('agent-current',?,?,?,'ACTIVE',4,?,?)
                """, ownerId, tenantId, "Current Agent", now, now);
        insertVersion(jdbc, publishedId, 1, "PUBLISHED", "old prompt", "a", ownerId, now);
        insertVersion(jdbc, draftId, 2, "DRAFT", "latest saved prompt", "b", ownerId, now);
        jdbc.update("""
                UPDATE platform_agent_definitions
                   SET current_agent_version_id=CAST(? AS UUID)
                 WHERE id='agent-current'
                """, publishedId);
        jdbc.update("""
                INSERT INTO platform_agent_runs(
                    id,agent_id,owner_id,conversation_id,state,created_at,updated_at,
                    agent_version_id,tenant_id)
                VALUES('run-versioned','agent-current',?,'conversation-versioned','COMPLETED',?,?,
                       CAST(? AS UUID),?)
                """, ownerId, now, now, publishedId, tenantId);
        jdbc.update("""
                INSERT INTO platform_agent_runs(
                    id,agent_id,owner_id,conversation_id,state,created_at,updated_at,
                    agent_version_id,tenant_id)
                VALUES('run-legacy','agent-current',?,'conversation-legacy','FAILED',?,?,NULL,?)
                """, ownerId, now, now, tenantId);

        migrateExisting();

        assertThat(jdbc.queryForObject("""
                SELECT system_prompt FROM platform_agent_current_configurations
                WHERE agent_id='agent-current'
                """, String.class)).isEqualTo("latest saved prompt");
        assertThat(jdbc.queryForObject("""
                SELECT config_hash FROM platform_agent_current_configurations
                WHERE agent_id='agent-current'
                """, String.class)).isEqualTo("b".repeat(64));
        assertThat(jdbc.queryForObject("""
                SELECT snapshot_state || ':' || system_prompt
                FROM platform_agent_run_configuration_snapshots
                WHERE run_id='run-versioned'
                """, String.class)).isEqualTo("SNAPSHOTTED:old prompt");
        assertThat(jdbc.queryForObject("""
                SELECT snapshot_state FROM platform_agent_run_configuration_snapshots
                WHERE run_id='run-legacy'
                """, String.class)).isEqualTo("LEGACY_UNSNAPSHOTTED");
        assertThat(table(jdbc, "platform_agent_versions")).isFalse();
        assertThat(latestVersion(jdbc)).isEqualTo("1098");
    }

    private static void insertVersion(
            JdbcTemplate jdbc,
            String id,
            int number,
            String status,
            String prompt,
            String hashCharacter,
            String ownerId,
            Timestamp now) {
        jdbc.update("""
                INSERT INTO platform_agent_versions(
                    id,agent_id,version_number,status,config_hash,system_prompt,
                    model_pool_id,model_provider_id,model_id,temperature,
                    max_context_tokens,max_output_tokens,max_turns,permission_mode,
                    memory_enabled,rag_enabled,network_enabled,knowledge_base_ids,
                    enabled_tool_ids,skill_ids,source_agent_revision,created_by,created_at)
                VALUES(CAST(? AS UUID),'agent-current',?,?,?,'placeholder',NULL,NULL,NULL,0.2,
                       200000,4096,25,'ask',TRUE,FALSE,TRUE,'[]','[]','[]',4,?,?)
                """, id, number, status, hashCharacter.repeat(64), ownerId, now);
        jdbc.update("UPDATE platform_agent_versions SET system_prompt=? WHERE id=CAST(? AS UUID)",
                prompt, id);
    }

    private static JdbcTemplate migrate(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        configuration.load().migrate();
        return jdbc();
    }

    private static void migrateExisting() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
    }

    private static void clean() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static boolean table(JdbcTemplate jdbc, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, name));
    }

    private static String latestVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }
}
