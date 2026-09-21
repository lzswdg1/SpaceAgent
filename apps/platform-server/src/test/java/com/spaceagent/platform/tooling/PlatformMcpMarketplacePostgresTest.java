package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.application.McpMarketplaceApplicationService;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpCatalogSourceType;
import com.spaceagent.platform.tooling.domain.McpMarketplaceTrustTier;
import com.spaceagent.platform.tooling.domain.McpServerVersionState;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresMcpMarketplaceRepository;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresToolingCleanupService;
import com.spaceagent.shared.id.UuidGenerator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PlatformMcpMarketplacePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("mcp_marketplace")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
    }

    @Test
    void persistsEncryptedAuthRevisionCasCatalogProfileAndTenantCleanup() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        var ids = new UuidGenerator();
        var identities = new PostgresIdentityRepository(jdbc);
        var identity = new IdentityApplicationService(identities, ids, Instant::now);
        String tenant = identity.createTenant(new CreateTenantCommand("MCP", "mcp-pg")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "mcp@example.com", "MCP")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var repository = new PostgresMcpMarketplaceRepository(jdbc);
        var api = new McpMarketplaceApplicationService(
                repository, identity, new Cipher(), new ObjectMapper(), ids, Instant::now);
        var entry = repository.findEntryBySlug("github").orElseThrow();
        assertEquals(GithubMcpProfiles.OFFICIAL_REMOTE_ENDPOINT, entry.defaultEndpoint());
        assertTrue(entry.manifestJson().contains("host_oauth_2_1_pkce"));
        assertEquals("com.github", entry.publisherNamespace());
        assertEquals(McpCatalogSourceType.BUILT_IN, entry.sourceType());
        assertEquals(McpMarketplaceTrustTier.PLATFORM_CURATED, entry.trustTier());
        assertEquals("1.0.0", entry.currentVersion());
        assertTrue(entry.currentManifestSha256().matches("[0-9a-f]{64}"));
        var versions = api.versions(entry.id());
        assertEquals(1, versions.size());
        assertEquals(McpServerVersionState.APPROVED, versions.getFirst().lifecycleState());
        assertEquals(entry.currentVersionId(), versions.getFirst().id());
        assertEquals(1, versions.getFirst().transports().size());
        assertEquals(GithubMcpProfiles.OFFICIAL_REMOTE_ENDPOINT,
                versions.getFirst().transports().getFirst().endpointTemplate());
        var installation = api.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, entry.id(), McpInstallationScope.ORGANIZATION, null));
        assertEquals(entry.currentVersionId(), installation.serverVersionId());
        assertEquals("1.0.0", installation.serverVersion());
        assertNotNull(jdbc.queryForObject("""
                SELECT server_version_id FROM platform_mcp_installations
                 WHERE id = CAST(? AS UUID)
                """, String.class, installation.id()));

        String nextVersionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_mcp_server_versions(
                    id, entry_id, version, source_type, source_uri, manifest_schema_uri,
                    manifest_json, manifest_sha256, auth_type, lifecycle_state,
                    published_at, created_at)
                VALUES(CAST(? AS UUID), CAST(? AS UUID), '2.0.0', 'BUILT_IN', NULL, NULL,
                    '{}'::jsonb, ?, 'OAUTH2', 'APPROVED', clock_timestamp(), clock_timestamp())
                """, nextVersionId, entry.id(), "b".repeat(64));
        jdbc.update("""
                UPDATE platform_mcp_marketplace_entries
                   SET current_version_id = CAST(? AS UUID), revision = revision + 1
                 WHERE id = CAST(? AS UUID)
                """, nextVersionId, entry.id());
        var reinstalled = api.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, entry.id(), McpInstallationScope.ORGANIZATION, null));
        assertEquals(installation.serverVersionId(), reinstalled.serverVersionId());
        assertThrows(com.spaceagent.shared.exception.BusinessException.class,
                () -> api.install(new McpMarketplaceApplicationApi.InstallCommand(
                        tenant, user, entry.id(), nextVersionId,
                        McpInstallationScope.ORGANIZATION, null)));

        String otherVersion = repository.findEntryBySlug("custom-streamable-http")
                .orElseThrow().currentVersionId();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        UPDATE platform_mcp_installations
                           SET server_version_id = CAST(? AS UUID)
                         WHERE id = CAST(? AS UUID)
                        """, otherVersion, installation.id()));
        McpConnection connection = repository.findConnection(api.connect(
                new McpMarketplaceApplicationApi.ConnectCommand(
                        tenant, user, installation.id(), "https://mcp.github.example/rpc",
                        McpAuthType.OAUTH2, Map.of("token", "pg-secret"))).id()).orElseThrow();
        String encrypted = jdbc.queryForObject(
                "SELECT encrypted_auth_json FROM platform_mcp_connections "
                        + "WHERE installation_id=CAST(? AS UUID)",
                String.class, installation.id());
        assertFalse(encrypted.contains("pg-secret"));

        McpConnection rotated = new McpConnection(
                connection.id(), connection.installationId(), connection.tenantId(),
                connection.managedBy(), connection.endpointUrl(), connection.encryptedAuthJson(),
                connection.authType(), connection.state(), connection.externalAccountId(),
                connection.externalAccountName(), connection.revision() + 1,
                connection.createdAt(), Instant.now(), connection.revokedAt());
        assertFalse(repository.saveConnectionIfRevision(rotated, connection.revision() + 1));
        assertTrue(repository.saveConnectionIfRevision(rotated, connection.revision()));
        assertEquals(rotated.revision(),
                repository.findConnection(connection.id()).orElseThrow().revision());

        jdbc.update("""
                UPDATE platform_mcp_marketplace_entries SET trust_tier = 'UNVERIFIED'
                 WHERE id = CAST(? AS UUID)
                """, entry.id());
        assertThrows(com.spaceagent.shared.exception.BusinessException.class,
                () -> api.install(new McpMarketplaceApplicationApi.InstallCommand(
                        tenant, user, entry.id(), McpInstallationScope.ORGANIZATION, null)));

        assertEquals(110L, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class));
        new PostgresToolingCleanupService(jdbc).cleanupOrganization(tenant);
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_installations WHERE tenant_id=?",
                Long.class, tenant));
        assertTrue(identities.findUserById(user).isPresent());
    }

    @Test
    void v1039BackfillsAnExistingInstallationWithItsCurrentServerVersion() {
        String schema = "mcp_v1038_upgrade";
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1038"))
                .load().migrate();

        JdbcTemplate jdbc = new JdbcTemplate(
                com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var identities = new PostgresIdentityRepository(jdbc);
        var identity = new IdentityApplicationService(identities, new UuidGenerator(), Instant::now);
        String tenant = identity.createTenant(new CreateTenantCommand(
                "MCP Upgrade", "mcp-upgrade-" + suffix)).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "mcp-upgrade-" + suffix + "@example.com", "MCP Upgrade")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));

        String installationId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_mcp_installations(
                    id, entry_id, tenant_id, subject_id, created_by, scope,
                    display_name, state, created_at, updated_at)
                VALUES(CAST(? AS UUID), '26000000-0000-4000-8000-000000000001', ?, ?, ?,
                    'USER', 'Pinned before V1039', 'INSTALLED', clock_timestamp(), clock_timestamp())
                """, installationId, tenant, user, user);
        String unknownEntryId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO platform_mcp_marketplace_entries(
                    id, slug, name, description, transport, auth_type, default_endpoint,
                    manifest_json, enabled, created_at, updated_at)
                VALUES(CAST(? AS UUID), 'legacy-private', 'Legacy Private', 'Manual legacy row',
                    'STREAMABLE_HTTP', 'BEARER', 'https://private.example/mcp', '{}'::jsonb,
                    TRUE, clock_timestamp(), clock_timestamp())
                """, unknownEntryId);

        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1039"))
                .load().migrate();

        Map<String, Object> evidence = jdbc.queryForMap("""
                SELECT installation.server_version_id::text AS pinned_version,
                       entry.current_version_id::text AS current_version,
                       version.version
                  FROM platform_mcp_installations installation
                  JOIN platform_mcp_marketplace_entries entry
                    ON entry.id = installation.entry_id
                  JOIN platform_mcp_server_versions version
                    ON version.id = installation.server_version_id
                 WHERE installation.id = CAST(? AS UUID)
                """, installationId);
        assertEquals(evidence.get("current_version"), evidence.get("pinned_version"));
        assertEquals("1.0.0", evidence.get("version"));
        assertEquals(Map.of(
                        "source_type", "PRIVATE",
                        "trust_tier", "UNVERIFIED",
                        "lifecycle_state", "CANDIDATE"),
                jdbc.queryForMap("""
                        SELECT entry.source_type, entry.trust_tier, version.lifecycle_state
                          FROM platform_mcp_marketplace_entries entry
                          JOIN platform_mcp_server_versions version
                            ON version.id = entry.current_version_id
                         WHERE entry.id = CAST(? AS UUID)
                        """, unknownEntryId));
        assertEquals("1039", jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                 WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class));
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static final class Cipher implements McpConnectionSecretCipher {
        public String encrypt(String value) {
            return "cipher:" + Base64.getEncoder().encodeToString(
                    value.getBytes(StandardCharsets.UTF_8));
        }
        public String decrypt(String value) {
            return new String(Base64.getDecoder().decode(value.substring(7)),
                    StandardCharsets.UTF_8);
        }
    }
}
