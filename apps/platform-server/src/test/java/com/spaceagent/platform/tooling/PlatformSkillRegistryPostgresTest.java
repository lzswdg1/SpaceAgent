package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.SkillRegistryApplicationApi;
import com.spaceagent.platform.tooling.application.RuntimeCapabilityCatalogApplicationService;
import com.spaceagent.platform.tooling.application.SkillRegistryApplicationService;
import com.spaceagent.platform.tooling.infrastructure.DisabledWebSearchGateway;
import com.spaceagent.platform.tooling.infrastructure.SandboxProperties;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresSkillRegistryRepository;
import com.spaceagent.shared.id.UuidGenerator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PlatformSkillRegistryPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-07T01:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("skill_registry")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @Test
    void persistsPublishedVersionsAndUpgradesV1050() {
        String schema = "skill_v1050_upgrade";
        migrate(schema, "1050");
        DriverManagerDataSource dataSource = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var ids = new UuidGenerator();
        String tenant = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_tenants(id, name, slug, status, created_at, updated_at)
                VALUES (?, 'Skills', ?, 'ACTIVE', ?, ?)
                """, tenant, "skills-" + tenant.substring(0, 8),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_users(
                    id, tenant_id, external_id, display_name, created_at, updated_at)
                VALUES (?, ?, 'skills@example.com', 'Skills', ?, ?)
                """, user, tenant, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_tenant_memberships(
                    tenant_id, user_id, tenant_role, status, joined_at, updated_at)
                VALUES (?, ?, 'OWNER', 'ACTIVE', ?, ?)
                """, tenant, user, Timestamp.from(NOW), Timestamp.from(NOW));

        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1051"))
                .load().migrate();
        var repository = new PostgresSkillRegistryRepository(jdbc, new ObjectMapper());
        var catalog = new RuntimeCapabilityCatalogApplicationService(
                new ObjectMapper(), new DisabledWebSearchGateway(), new SandboxProperties(), repository);
        var service = new SkillRegistryApplicationService(repository, catalog, ids, () -> NOW);
        var skill = service.create(new SkillRegistryApplicationApi.CreateSkillCommand(
                tenant, user, "PostgreSQL Skill", "fixture", "Read repository state.",
                List.of("echo")));
        String versionId = skill.versions().getFirst().id();
        var published = service.publish(new SkillRegistryApplicationApi.SkillVersionLifecycleCommand(
                tenant, user, skill.id(), versionId));

        assertThat(published.currentVersionId()).isEqualTo(versionId);
        assertThat(catalog.supportsSkill(versionId, tenant)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("1051");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL",
                Long.class)).isEqualTo(63L);
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, config_hash, required_tool_ids
                  FROM platform_skill_versions WHERE id = CAST(? AS UUID)
                """, versionId))
                .containsEntry("lifecycle_state", "PUBLISHED");

        service.deprecate(new SkillRegistryApplicationApi.SkillVersionLifecycleCommand(
                tenant, user, skill.id(), versionId));
        service.archive(new SkillRegistryApplicationApi.ArchiveSkillCommand(
                tenant, user, skill.id()));
        var restartedRepository = new PostgresSkillRegistryRepository(jdbc, new ObjectMapper());
        var restartedCatalog = new RuntimeCapabilityCatalogApplicationService(
                new ObjectMapper(), new DisabledWebSearchGateway(),
                new SandboxProperties(), restartedRepository);
        assertThat(restartedCatalog.resolvePinnedSkills(
                tenant, List.of(versionId), List.of("echo")))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.id()).isEqualTo(versionId);
                    assertThat(value.instructions()).isEqualTo("Read repository state.");
                    assertThat(value.configHash()).hasSize(64);
                });
    }

    @Test
    void freshMigrationCreatesCurrentVersionCompositeConstraint() {
        String schema = "skill_v1051_fresh";
        migrate(schema, "1051");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                 WHERE constraint_schema = ?
                   AND constraint_name = 'fk_platform_skill_definition_current_version'
                """, Long.class, "public")).isEqualTo(1L);
    }

    private static void migrate(String schema, String target) {
        var configuration = Flyway.configure().dataSource(
                        com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime");
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        configuration.load().migrate();
    }

    private static DriverManagerDataSource dataSource(String schema) {
        return com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema);
    }
}
