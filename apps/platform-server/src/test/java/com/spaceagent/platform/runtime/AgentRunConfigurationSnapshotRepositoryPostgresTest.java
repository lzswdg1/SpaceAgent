package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshot;
import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresAgentRunConfigurationSnapshotRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AgentRunConfigurationSnapshotRepositoryPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-11T08:30:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("agent_run_configuration_snapshot")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    private JdbcTemplate jdbc;
    private String tenantId;
    private String ownerId;

    @BeforeEach
    void migrateAndSeed() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        tenantId = UUID.randomUUID().toString();
        ownerId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("""
                INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at)
                VALUES(?,?,?,'ACTIVE',?,?)
                """, tenantId, "Personal", "run-snapshot-" + tenantId, now, now);
        jdbc.update("""
                INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES(?,?,?,'Owner',?,?)
                """, ownerId, tenantId, ownerId + "@example.com", now, now);
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,status,revision,created_at,updated_at)
                VALUES('agent-run-snapshot',?,?,?,'ACTIVE',1,?,?)
                """, ownerId, tenantId, "Agent", now, now);
        jdbc.update("""
                INSERT INTO platform_agent_runs(
                    id,agent_id,owner_id,conversation_id,state,created_at,updated_at,tenant_id)
                VALUES('run-snapshot','agent-run-snapshot',?,'conversation','RUNNING',?,?,?)
                """, ownerId, now, now, tenantId);
    }

    @Test
    void snapshotIsInsertOnceScopeBoundAndRestartReadable() {
        var repository = repository();
        AgentRunConfigurationSnapshot snapshot = snapshot("a", "prompt");

        assertThat(repository.insertIfAbsent(snapshot)).isEqualTo(snapshot);
        assertThat(repository.insertIfAbsent(snapshot)).isEqualTo(snapshot);
        assertThat(repository.findByRunId(tenantId, "other", "run-snapshot")).isEmpty();
        assertThat(repository().findByRunId(tenantId, ownerId, "run-snapshot"))
                .contains(snapshot);

        assertThatThrownBy(() -> repository.insertIfAbsent(snapshot("b", "changed")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflict");
    }

    private PostgresAgentRunConfigurationSnapshotRepository repository() {
        return new PostgresAgentRunConfigurationSnapshotRepository(jdbc, new ObjectMapper());
    }

    @Test
    void repositoryAttachmentRemainsQueryableAfterLaterCheckpointsAndRepositoryRecreation() {
        var mapper = new ObjectMapper();
        var ledger = new com.spaceagent.platform.runtime.infrastructure.persistence.PostgresRuntimeLedgerRepository(jdbc, mapper);
        String binding = "{\"phase\":\"project-chat-workspace-v1\",\"binding\":{\"workspaceId\":\"workspace-fixture\"}}";
        ledger.saveCheckpoint(new com.spaceagent.platform.runtime.domain.Checkpoint("repo-binding", "run-snapshot", 0, binding, NOW));
        ledger.saveCheckpoint(new com.spaceagent.platform.runtime.domain.Checkpoint("repo-answer", "run-snapshot", 1,
                "{\"phase\":\"completed\"}", NOW));
        var reopened = new com.spaceagent.platform.runtime.infrastructure.persistence.PostgresRuntimeLedgerRepository(jdbc, mapper);
        assertThat(reopened.findLatestCheckpointByPhase("run-snapshot", "project-chat-workspace-v1"))
                .get().extracting(com.spaceagent.platform.runtime.domain.Checkpoint::stateSnapshot).isEqualTo(binding);
        assertThat(reopened.findLatestCheckpointByPhase("different-run", "project-chat-workspace-v1")).isEmpty();
    }

    private AgentRunConfigurationSnapshot snapshot(String hashCharacter, String prompt) {
        return new AgentRunConfigurationSnapshot(
                "run-snapshot", "run-snapshot", tenantId, ownerId, "agent-run-snapshot",
                AgentRunConfigurationSnapshot.State.SNAPSHOTTED, 1L,
                hashCharacter.repeat(64), null, null, null, prompt, 0.2,
                200_000, 4096, 25, true, false, true,
                List.of("knowledge"), List.of("web_search"), List.of(), "ask",
                List.of(), ownerId, NOW, NOW.plusSeconds(1), List.of("collection-a"));
    }
}
