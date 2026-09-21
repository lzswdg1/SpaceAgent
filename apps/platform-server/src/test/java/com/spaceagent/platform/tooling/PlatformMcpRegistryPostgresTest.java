package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.McpRegistryAdministrationApi;
import com.spaceagent.platform.tooling.application.McpRegistryAdministrationService;
import com.spaceagent.platform.tooling.domain.McpRegistryCompatibility;
import com.spaceagent.platform.tooling.domain.McpRegistryGateway;
import com.spaceagent.platform.tooling.domain.McpRegistryGateway.RegistryServer;
import com.spaceagent.platform.tooling.domain.McpRegistrySnapshot;
import com.spaceagent.platform.tooling.domain.McpRegistryStatus;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncState;
import com.spaceagent.platform.tooling.infrastructure.McpToolingProperties;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresMcpMarketplaceRepository;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresMcpRegistryRepository;
import com.spaceagent.shared.id.UuidGenerator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PlatformMcpRegistryPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("mcp_registry")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
    }

    @Test
    void persistsIdempotentSnapshotsAndPublishesOnlyAfterReview() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var registry = new PostgresMcpRegistryRepository(jdbc, json);
        var marketplace = new PostgresMcpMarketplaceRepository(jdbc);
        var service = new McpRegistryAdministrationService(
                registry, marketplace, new FixtureGateway("digest-a"),
                new McpToolingProperties(), new UuidGenerator(),
                () -> Instant.parse("2026-09-05T00:00:00Z"));
        String actor = UUID.randomUUID().toString();

        service.enqueue(actor);
        assertThat(service.runOnce("registry-pg-worker")).isTrue();
        var candidate = service.candidates(0, 10, null, null).items().getFirst();
        assertThat(marketplace.findEntryByRegistryName(candidate.registryName())).isEmpty();
        var approved = inTransaction(jdbc, () -> service.approve(
                new McpRegistryAdministrationApi.ReviewCommand(
                        candidate.id(), actor, "Verified repository and remote endpoint")));
        assertThat(approved.publishedEntryId()).isNotBlank();
        assertThat(approved.publishedVersionId()).isNotBlank();
        assertThat(marketplace.findEntryByRegistryName(candidate.registryName()))
                .get().satisfies(entry -> {
                    assertThat(entry.currentVersion()).isEqualTo("1.0.0");
                    assertThat(entry.currentManifestSha256()).isEqualTo(candidate.manifestSha256());
                });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_registry_snapshots", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_registry_candidates", Long.class)).isEqualTo(1L);

        service.enqueue(actor);
        service.runOnce("registry-pg-worker");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_registry_snapshots", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_registry_candidates", Long.class)).isEqualTo(1L);
        assertThat(service.syncJobs(0, 10, McpRegistrySyncState.SUCCEEDED).total()).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(110L);
    }

    @Test
    void rejectsChangedManifestForAnAlreadyPublishedVersion() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var registry = new PostgresMcpRegistryRepository(jdbc, json);
        var marketplace = new PostgresMcpMarketplaceRepository(jdbc);
        String name = "io.github.example/conflict-" + UUID.randomUUID().toString().substring(0, 8);
        String actor = UUID.randomUUID().toString();
        var first = service(registry, marketplace, new NamedGateway(name, "first"));
        first.enqueue(actor);
        first.runOnce("registry-conflict-worker");
        var initial = first.candidates(0, 10, null, name).items().getFirst();
        inTransaction(jdbc, () -> first.approve(new McpRegistryAdministrationApi.ReviewCommand(
                initial.id(), actor, "Approve original immutable version")));

        var changed = service(registry, marketplace, new NamedGateway(name, "changed"));
        changed.enqueue(actor);
        changed.runOnce("registry-conflict-worker");
        var next = changed.candidates(0, 10, null, name).items().stream()
                .filter(value -> !value.id().equals(initial.id())).findFirst().orElseThrow();
        assertThatThrownBy(() -> inTransaction(jdbc, () -> changed.approve(
                new McpRegistryAdministrationApi.ReviewCommand(
                        next.id(), actor, "Attempt replacement of immutable version"))))
                .isInstanceOfSatisfying(com.spaceagent.shared.exception.BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_REGISTRY_VERSION_IMMUTABLE_CONFLICT"));
    }

    @Test
    void upgradesV1041WithoutMutatingExistingMarketplaceVersions() {
        String schema = "mcp_registry_upgrade";
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1041")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
                com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));
        String before = jdbc.queryForObject("""
                SELECT manifest_sha256 FROM platform_mcp_server_versions
                 WHERE id = '10390000-0000-4000-8000-000000000101'
                """, String.class);
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1042")).load().migrate();
        assertThat(jdbc.queryForObject("""
                SELECT manifest_sha256 FROM platform_mcp_server_versions
                 WHERE id = '10390000-0000-4000-8000-000000000101'
                """, String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_mcp_registry_sources
                 WHERE source_key = 'official' AND enabled
                """, Long.class)).isEqualTo(1L);
    }

    private static McpRegistryAdministrationService service(
            PostgresMcpRegistryRepository registry,
            PostgresMcpMarketplaceRepository marketplace,
            McpRegistryGateway gateway) {
        return new McpRegistryAdministrationService(
                registry, marketplace, gateway, new McpToolingProperties(),
                new UuidGenerator(), () -> Instant.parse("2026-09-05T00:00:00Z"));
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static <T> T inTransaction(JdbcTemplate jdbc, Supplier<T> action) {
        return new TransactionTemplate(new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource())))
                .execute(status -> action.get());
    }

    private static class FixtureGateway implements McpRegistryGateway {
        protected final String marker;

        private FixtureGateway(String marker) {
            this.marker = marker;
        }

        @Override
        public RegistryPage fetchPage(String baseUrl, Instant updatedSince, String cursor, int limit) {
            return new RegistryPage(List.of(value("io.github.example/postgres", marker)), null);
        }
    }

    private static final class NamedGateway extends FixtureGateway {
        private final String name;

        private NamedGateway(String name, String marker) {
            super(marker);
            this.name = name;
        }

        @Override
        public RegistryPage fetchPage(String baseUrl, Instant updatedSince, String cursor, int limit) {
            return new RegistryPage(List.of(value(name, super.marker)), null);
        }
    }

    private static RegistryServer value(String name, String marker) {
        return new RegistryServer(
                name, "1.0.0", McpRegistryStatus.ACTIVE, null, "Postgres Registry",
                "Postgres Registry fixture", "https://schema.example/server.json",
                "https://github.com/example/server",
                "{\"marker\":\"" + marker + "\",\"server\":{\"name\":\"" + name
                        + "\",\"version\":\"1.0.0\"}}",
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"),
                List.of(new McpRegistrySnapshot.RemoteTransport(
                        "https://mcp.example.com/rpc", "{}", "{}", false)),
                McpRegistryCompatibility.SUPPORTED_REMOTE, null);
    }
}
