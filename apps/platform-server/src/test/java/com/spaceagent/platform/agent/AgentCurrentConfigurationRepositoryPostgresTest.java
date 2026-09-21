package com.spaceagent.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.domain.AgentCurrentConfiguration;
import com.spaceagent.platform.agent.infrastructure.persistence.PostgresAgentCurrentConfigurationRepository;
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
class AgentCurrentConfigurationRepositoryPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-11T08:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("agent_current_repository")
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
                """, tenantId, "Personal", "current-" + tenantId, now, now);
        jdbc.update("""
                INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES(?,?,?,'Owner',?,?)
                """, ownerId, tenantId, ownerId + "@example.com", now, now);
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,status,revision,created_at,updated_at)
                VALUES('agent-current',?,?,?,'ACTIVE',1,?,?)
                """, ownerId, tenantId, "Agent", now, now);
    }

    @Test
    void insertUpdateCasScopeAndRestartReadAreAuthoritative() {
        var repository = repository();
        AgentCurrentConfiguration first = configuration(1, "a", "prompt one", NOW);
        repository.insert(first);

        assertThat(repository.find(tenantId, ownerId, "agent-current")).contains(first);
        assertThat(repository.find(tenantId, "other", "agent-current")).isEmpty();
        assertThatThrownBy(() -> repository.insert(first))
                .isInstanceOf(IllegalStateException.class);

        AgentCurrentConfiguration second = configuration(2, "b", "prompt two", NOW.plusSeconds(1));
        assertThat(repository.update(second, 7)).isEmpty();
        assertThat(repository.update(second, 1)).contains(second);
        assertThat(repository.update(configuration(3, "c", "stale", NOW.plusSeconds(2)), 1)).isEmpty();

        var restarted = repository();
        assertThat(restarted.find(tenantId, ownerId, "agent-current"))
                .get().extracting(AgentCurrentConfiguration::systemPrompt)
                .isEqualTo("prompt two");
        assertThat(restarted.findByTenantAndAgentIds(
                tenantId, List.of("agent-current", "missing")))
                .containsExactly(second);
    }

    private PostgresAgentCurrentConfigurationRepository repository() {
        return new PostgresAgentCurrentConfigurationRepository(jdbc, new ObjectMapper());
    }

    private AgentCurrentConfiguration configuration(
            long revision, String hashCharacter, String prompt, Instant updatedAt) {
        return new AgentCurrentConfiguration(
                "agent-current", ownerId, tenantId, revision, hashCharacter.repeat(64),
                null, null, null, prompt, 0.2, 200_000, 4096, 25,
                true, false, true, List.of("knowledge-b", "knowledge-a"),
                List.of("web_search"), List.of(), "ask", List.of(), ownerId, updatedAt, List.of("collection-" + revision));
    }
}
