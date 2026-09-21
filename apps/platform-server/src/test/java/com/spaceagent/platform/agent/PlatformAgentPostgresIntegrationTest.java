package com.spaceagent.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.CreateAgentApiKeyCommand;
import com.spaceagent.platform.agent.api.CreateAgentDefinitionCommand;
import com.spaceagent.platform.agent.api.RevokeAgentApiKeyCommand;
import com.spaceagent.platform.agent.application.AgentApiKeyApplicationService;
import com.spaceagent.platform.agent.application.AgentApplicationService;
import com.spaceagent.platform.agent.application.AgentConfigurationFactory;
import com.spaceagent.platform.agent.infrastructure.persistence.PostgresAgentApiKeyRepository;
import com.spaceagent.platform.agent.infrastructure.persistence.PostgresAgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.infrastructure.persistence.PostgresAgentRepository;
import com.spaceagent.platform.agent.domain.AgentApiKeyScope;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL proof for M8c Phase 2 normalized knowledge bindings and hashed API keys.
 */
@Testcontainers(disabledWithoutDocker = true)
class PlatformAgentPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_agent_phase2")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(newDataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
    }

    @Test
    void v9PersistsNormalizedBindingsAndOnlyApiKeyHashes() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
        UuidGenerator idGenerator = new UuidGenerator();
        TimeProvider timeProvider = () -> NOW;
        PostgresAgentRepository agentRepository = new PostgresAgentRepository(jdbcTemplate);
        jdbcTemplate.update("""
                INSERT INTO platform_tenants (id, name, slug, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "tenant-1", "Tenant 1", "tenant-1", "ACTIVE",
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbcTemplate.update("""
                INSERT INTO platform_users (id, tenant_id, external_id, display_name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "owner-1", "tenant-1", "owner@example.com", "Owner",
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbcTemplate.update("""
                INSERT INTO platform_tenant_memberships (
                    tenant_id, user_id, tenant_role, status, joined_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, "tenant-1", "owner-1", "OWNER", "ACTIVE",
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        jdbcTemplate.update("""
                INSERT INTO platform_knowledge_documents (
                    id, owner_id, name, content_type, storage_location, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "knowledge-1", "owner-1", "knowledge.md", "text/markdown",
                "storage://knowledge.md", "READY",
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        AgentApplicationService agentService = new AgentApplicationService(
                agentRepository, idGenerator, timeProvider, null, null, null,
                new AgentConfigurationFactory(new ObjectMapper()), null,
                new PostgresAgentCurrentConfigurationRepository(jdbcTemplate, new ObjectMapper()));

        var agent = agentService.create(new CreateAgentDefinitionCommand(
                "owner-1", "tenant-1", "postgres-agent", null, "Build safely",
                null, null, 0.2, 4096, 25, "private",
                true, true, false,
                List.of("knowledge-1"), List.of(), List.of()));

        assertEquals(List.of("knowledge-1"),
                agentService.findById(agent.id()).orElseThrow().knowledgeBaseIds());
        Number bindings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_agent_knowledge_bindings WHERE agent_id = ?",
                Number.class,
                agent.id());
        assertEquals(0L, bindings.longValue());
        assertEquals("Build safely", jdbcTemplate.queryForObject(
                "SELECT system_prompt FROM platform_agent_current_configurations WHERE agent_id = ?",
                String.class, agent.id()));

        PostgresAgentApiKeyRepository apiKeyRepository = new PostgresAgentApiKeyRepository(jdbcTemplate);
        AgentApiKeyApplicationService keyService = new AgentApiKeyApplicationService(
                apiKeyRepository, agentRepository, idGenerator, timeProvider);
        var created = keyService.create(new CreateAgentApiKeyCommand(
                "tenant-1", "owner-1", agent.id(), "postgres-key",
                Set.of(AgentApiKeyScope.CHAT), NOW.plus(1, ChronoUnit.DAYS)));
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT key_hash FROM platform_agent_api_keys WHERE id = ?",
                String.class,
                created.apiKey().id());

        assertNotEquals(created.rawKey(), storedHash);
        assertEquals(64, storedHash.length());
        assertTrue(keyService.verify(created.rawKey()).isPresent());

        keyService.revoke(new RevokeAgentApiKeyCommand(
                "tenant-1", "owner-1", agent.id(), created.apiKey().id()));
        assertFalse(keyService.verify(created.rawKey()).isPresent());
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
