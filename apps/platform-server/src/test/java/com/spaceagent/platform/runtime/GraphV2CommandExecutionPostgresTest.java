package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.domain.GraphSessionState;
import com.spaceagent.platform.runtime.domain.RuntimeGraphCommand;
import com.spaceagent.platform.runtime.domain.RuntimeGraphSession;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresRuntimeGraphSessionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class GraphV2CommandExecutionPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final String HASH = "sha256:" + "a".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("graph_command_execution")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = dataSource(null);
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
    }

    @Test
    void persistsCommandExecutionFenceAcrossRepositoryRestart() {
        var repository = new PostgresRuntimeGraphSessionRepository(
                new JdbcTemplate(dataSource), new ObjectMapper());
        String sessionId = UUID.randomUUID().toString();
        repository.insert(RuntimeGraphSession.start(
                sessionId, "tenant", "owner", UUID.randomUUID().toString(), HASH, NOW));
        var command = new RuntimeGraphCommand(sessionId, "command", HASH,
                GraphV2Contract.Kind.TOOL_REQUESTED, 1, "{\"toolId\":\"echo\"}",
                RuntimeGraphCommand.State.PENDING, 0, null, null, null, null,
                1, NOW, NOW);
        repository.insertCommandIfAbsent(command);
        var executing = command.begin("worker", UUID.randomUUID().toString(), 7,
                NOW.plusSeconds(1));
        assertThat(repository.updateCommand(
                executing, command.revision(), command.state())).isTrue();

        var restarted = new PostgresRuntimeGraphSessionRepository(
                new JdbcTemplate(dataSource), new ObjectMapper());
        assertThat(restarted.findCommand(sessionId, "command")).contains(executing);
        assertThat(restarted.updateCommand(executing.blockUnknown(NOW.plusSeconds(2)),
                executing.revision(), executing.state())).isTrue();
        assertThat(restarted.findCommand(sessionId, "command").orElseThrow().state())
                .isEqualTo(RuntimeGraphCommand.State.UNKNOWN);
    }

    @Test
    void v1076BlocksLegacyPendingCommandInsteadOfReplayingItAfterUpgrade() {
        String schema = "graph_command_upgrade";
        Flyway.configure().dataSource(dataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1075")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_runtime_graph_sessions(
                  id,tenant_id,owner_id,agent_run_id,bundle_hash,cursor_sequence,
                  completed_command_ids,pending_command_id,pending_input_hash,state,
                  revision,created_at,updated_at)
                VALUES(CAST(? AS UUID),'tenant','owner',?,?,1,'[]'::jsonb,'command',?,
                  'WAITING_FOR_COMMAND',2,?,?)
                """, sessionId, UUID.randomUUID().toString(), HASH, HASH,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_runtime_graph_commands(
                  graph_session_id,command_id,input_hash,state,revision,created_at,updated_at)
                VALUES(CAST(? AS UUID),'command',?,'PENDING',1,?,?)
                """, sessionId, HASH, Timestamp.from(NOW), Timestamp.from(NOW));

        Flyway flyway = Flyway.configure().dataSource(dataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1076")).load();
        flyway.migrate();
        flyway.migrate();

        assertThat(jdbc.queryForObject("""
                SELECT state FROM platform_runtime_graph_commands
                WHERE graph_session_id=CAST(? AS UUID) AND command_id='command'
                """, String.class, sessionId)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM platform_runtime_graph_sessions WHERE id=CAST(? AS UUID)
                """, String.class, sessionId)).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=? AND table_name='platform_runtime_graph_commands'
                  AND column_name IN ('command_kind','next_cursor_sequence','payload_json',
                                      'execution_owner','execution_token',
                                      'execution_fencing_token','safe_error_code','attempt')
                """, Long.class, "public")).isEqualTo(8);
    }

    private static DriverManagerDataSource dataSource(String schema) {
        return schema == null
                ? new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                : com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema);
    }
}
