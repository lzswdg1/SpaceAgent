package com.spaceagent.platform.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.CreateAgentDefinitionCommand;
import com.spaceagent.platform.agent.application.AgentApplicationService;
import com.spaceagent.platform.agent.application.AgentConfigurationFactory;
import com.spaceagent.platform.agent.infrastructure.persistence.PostgresAgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.infrastructure.persistence.PostgresAgentRepository;

import com.spaceagent.platform.inference.api.AddModelPoolMemberCommand;
import com.spaceagent.platform.inference.api.CreateModelPoolCommand;
import com.spaceagent.platform.inference.api.CreateModelProviderCommand;
import com.spaceagent.platform.inference.api.TestProviderConnectionCommand;
import com.spaceagent.platform.inference.api.UpdateModelPoolStatusCommand;
import com.spaceagent.platform.inference.application.InferenceApplicationService;
import com.spaceagent.platform.inference.application.ModelPoolApplicationService;
import com.spaceagent.platform.inference.application.ProviderConnectionApplicationService;
import com.spaceagent.platform.inference.application.ModelPricingApplicationService;
import com.spaceagent.platform.inference.api.ModelPricingApplicationApi;
import com.spaceagent.platform.inference.domain.ModelPoolRoutingStrategy;
import com.spaceagent.platform.inference.domain.ModelPoolVisibility;
import com.spaceagent.platform.inference.domain.ModelProviderSecretCipher;
import com.spaceagent.platform.inference.domain.ProviderConnectionProbeResult;
import com.spaceagent.platform.inference.infrastructure.persistence.PostgresInferenceProviderRepository;
import com.spaceagent.platform.inference.infrastructure.persistence.PostgresModelPoolRepository;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
class PlatformModelPoolPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-22T15:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_model_pool")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrateAndSeedIdentity() {
        Flyway.configure()
                .dataSource(newDataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO platform_tenants (id, name, slug, status, created_at, updated_at)
                VALUES ('tenant-1', 'Tenant 1', 'tenant-1', 'ACTIVE', ?, ?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_users (
                    id, tenant_id, external_id, display_name, created_at, updated_at
                ) VALUES ('owner-1', 'tenant-1', 'owner@example.com', 'Owner', ?, ?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    @Test
    void migrationPersistsProviderHealthPoolMembersResolutionAndDeleteProtection() {
        DriverManagerDataSource dataSource = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PostgresInferenceProviderRepository providerRepository =
                new PostgresInferenceProviderRepository(jdbc);
        PostgresModelPoolRepository poolRepository = new PostgresModelPoolRepository(jdbc);
        UuidGenerator ids = new UuidGenerator();
        TimeProvider time = () -> NOW;
        ObjectMapper objectMapper = new ObjectMapper();
        var healthProbes = new com.spaceagent.platform.inference.infrastructure.persistence.PostgresProviderHealthProbeRepository(
                jdbc, new DataSourceTransactionManager(dataSource), objectMapper);
        InferenceApplicationService inference = new InferenceApplicationService(
                providerRepository, poolRepository, new TestCipher(), value -> value, ids, time,
                healthProbes);
        ProviderConnectionApplicationService connection = new ProviderConnectionApplicationService(
                providerRepository,
                provider -> new ProviderConnectionProbeResult(
                        true, 7, List.of("model-a"), null),
                time, healthProbes, ids, new com.spaceagent.platform.inference.infrastructure.InferenceProperties());
        var priceRepository=new com.spaceagent.platform.inference.infrastructure.persistence.PostgresModelPriceRepository(jdbc);
        ModelPoolApplicationService pools = new ModelPoolApplicationService(
                poolRepository, providerRepository, ids, time,
                priceRepository);

        var provider = inference.createProvider(new CreateModelProviderCommand(
                "tenant-1", "owner-1", "postgres-provider", "openai-compatible",
                "https://example.com/v1", "secret", "bearer", true, false,
                List.of(new CreateModelProviderCommand.ProviderModelDraft(
                        "model-a", "Model A", 32768))));
        connection.testProvider(new TestProviderConnectionCommand("tenant-1", provider.id()));
        String pricedModel=inference.listModels(provider.id()).getFirst().id();
        var pricing=new ModelPricingApplicationService(priceRepository,providerRepository,ids,time);
        var price=pricing.createPrice(new ModelPricingApplicationApi.CreatePriceCommand(
                "tenant-1","owner-1",pricedModel,25,75,NOW,null));
        assertEquals(price.id(),priceRepository.findEffective(pricedModel,NOW).orElseThrow().id());
        var pool = pools.createPool(new CreateModelPoolCommand(
                "tenant-1", "owner-1", "postgres-pool",
                ModelPoolVisibility.ORGANIZATION, ModelPoolRoutingStrategy.PRIORITY, true));
        var member = pools.addMember(new AddModelPoolMemberCommand(
                "tenant-1", "owner-1", pool.id(), provider.id(),
                inference.listModels(provider.id()).getFirst().id(), 10, 1));
        pools.activatePool(new UpdateModelPoolStatusCommand(
                "tenant-1", "owner-1", pool.id()));

        PostgresAgentRepository agentRepository = new PostgresAgentRepository(jdbc);
        AgentApplicationService agents = new AgentApplicationService(
                agentRepository, ids, time, inference, pools, null,
                new AgentConfigurationFactory(objectMapper), null,
                new PostgresAgentCurrentConfigurationRepository(jdbc, objectMapper));
        var agent = agents.create(new CreateAgentDefinitionCommand(
                "owner-1", "tenant-1", "pool-bound-postgres-agent", "description",
                "Use the pool", pool.id(), null, null, 0.4, 4096, 20, "private",
                true, false, false, List.of(), List.of(), List.of()));
        assertEquals(pool.id(), agents.findById(agent.id()).orElseThrow().modelPoolId());

        PostgresModelPoolRepository reconnectedPools = new PostgresModelPoolRepository(
                new JdbcTemplate(newDataSource()));
        assertEquals(1, reconnectedPools.findMembersByPoolId(pool.id()).size());
        assertEquals(member.id(), reconnectedPools.findMembersByPoolId(pool.id()).getFirst().id());
        assertEquals("model-a", pools.resolvePool("tenant-1", "member-1", pool.id())
                .candidates().getFirst().modelId());
        assertThrows(com.spaceagent.shared.exception.BusinessException.class,
                () -> inference.deleteProvider("tenant-1", provider.id()));

        assertEquals("uuid", columnType(jdbc, "platform_model_pools", "id"));
        assertNotNull(jdbc.queryForObject("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_name = 'platform_model_pool_members'
                  AND constraint_name = 'fk_platform_model_pool_member_model'
                """, String.class));
        assertEquals("ACTIVE", jdbc.queryForObject("""
                SELECT connection_status FROM platform_model_providers WHERE id = ?
                """, String.class, provider.id()));
        assertEquals(pool.id(), jdbc.queryForObject("""
                SELECT model_pool_id::text FROM platform_agent_current_configurations
                WHERE agent_id = ?
                """, String.class, agent.id()));
        assertEquals("uuid", columnType(jdbc, "platform_agent_current_configurations", "model_pool_id"));
        assertNotNull(jdbc.queryForObject("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_name = 'platform_agent_current_configurations'
                  AND constraint_name = 'fk_agent_current_configuration_model_pool'
                """, String.class));
    }

    private static String columnType(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static final class TestCipher implements ModelProviderSecretCipher {
        @Override
        public String encrypt(String plaintext) {
            return "encrypted:" + plaintext;
        }

        @Override
        public String decrypt(String encoded) {
            return encoded.substring("encrypted:".length());
        }
    }
}
