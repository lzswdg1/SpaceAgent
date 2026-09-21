package com.spaceagent.platform.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.inference.api.CreateModelProviderCommand;
import com.spaceagent.platform.inference.application.InferenceApplicationService;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.inference.infrastructure.persistence.*;
import com.spaceagent.shared.id.UuidGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PlatformProviderHealthProbePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("spaceagent_provider_probes")
            .withUsername("spaceagent").withPassword("spaceagent");

    @Test
    void twoReplicasFenceClaimsAndPersistSafeObservation() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var ids = new UuidGenerator();
        var identities = new PostgresIdentityRepository(jdbc);
        var identity = new IdentityApplicationService(identities, ids, Instant::now);
        String tenant = identity.createTenant(new CreateTenantCommand("Probe", "probe-pg")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "probe@example.com", "Probe")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(tenant, user, TenantRole.OWNER));
        var providers = new PostgresInferenceProviderRepository(jdbc);
        var pools = new PostgresModelPoolRepository(jdbc);
        var probes = new PostgresProviderHealthProbeRepository(
                jdbc, new DataSourceTransactionManager(dataSource), new ObjectMapper());
        var inference = new InferenceApplicationService(
                providers, pools, new ModelProviderSecretCipher() {
                    public String encrypt(String value) { return "enc:" + value; }
                    public String decrypt(String value) { return value.substring(4); }
                }, value -> value,
                ids, Instant::now, probes);
        String providerId = inference.createProvider(new CreateModelProviderCommand(
                tenant, user, "probe-provider", "openai-compatible",
                "https://example.com/v1", "secret", "bearer", true, false,
                List.of(new CreateModelProviderCommand.ProviderModelDraft(
                        "model-a", "Model A", 32768)))).id();

        ProviderHealthProbeClaim first = probes.claimDue("replica-a", 60).orElseThrow();
        assertThat(probes.claimDue("replica-b", 60)).isEmpty();
        jdbc.update("UPDATE platform_provider_health_probe_jobs SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE provider_id=?", providerId);
        ProviderHealthProbeClaim reclaimed = probes.claimDue("replica-b", 60).orElseThrow();
        assertThat(reclaimed.fencingToken()).isGreaterThan(first.fencingToken());
        ProviderConnectionProbeResult result = new ProviderConnectionProbeResult(
                true, 17, List.of("model-a"), null);
        assertThat(probes.complete(new ProviderHealthProbeRepository.Completion(
                first, ids.nextId(), result, Instant.now(), 300, 30))).isFalse();
        assertThat(probes.complete(new ProviderHealthProbeRepository.Completion(
                reclaimed, ids.nextId(), result, Instant.now(), 300, 30))).isTrue();
        assertThat(providers.findProviderById(providerId).orElseThrow().connectionStatus())
                .isEqualTo(ProviderConnectionStatus.ACTIVE);
        assertThat(probes.findObservations(tenant, providerId, 10)).singleElement()
                .satisfies(value -> {
                    assertThat(value.latencyMs()).isEqualTo(17);
                    assertThat(value.errorCode()).isNull();
                    assertThat(value.discoveredModelIds()).containsExactly("model-a");
                });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(110L);
    }
}
