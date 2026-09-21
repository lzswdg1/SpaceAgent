package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.api.McpOAuthApplicationApi;
import com.spaceagent.platform.tooling.application.McpMarketplaceApplicationService;
import com.spaceagent.platform.tooling.application.McpOAuthApplicationService;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpOAuthClientAuthenticationMethod;
import com.spaceagent.platform.tooling.domain.McpOAuthClientGateway;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistration;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistrationProvider;
import com.spaceagent.platform.tooling.domain.McpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresMcpMarketplaceRepository;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresMcpOAuthStateRepository;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PlatformMcpOAuthPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-05T01:00:00Z");
    private static final String REDIRECT = "https://app.example/mcp/oauth/callback";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("mcp_oauth")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
    }

    @Test
    void bindsStateToRevisionRedactsPkceAndStoresOnlyEncryptedGrant() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        var ids = new UuidGenerator();
        var identity = new IdentityApplicationService(
                new PostgresIdentityRepository(jdbc), ids, () -> NOW);
        String tenant = identity.createTenant(
                new CreateTenantCommand("OAuth PG", "oauth-pg")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "generic-oauth-pg@example.com", "OAuth PG")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var marketplace = new PostgresMcpMarketplaceRepository(jdbc);
        var cipher = new Cipher();
        var mapper = new ObjectMapper();
        var catalog = new McpMarketplaceApplicationService(
                marketplace, identity, cipher, mapper, ids, () -> NOW);
        var installation = catalog.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user,
                marketplace.findEntryBySlug("custom-streamable-http").orElseThrow().id(),
                McpInstallationScope.USER, null));
        String connectionId = catalog.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), "https://mcp.acme.example/mcp",
                McpAuthType.OAUTH2, Map.of())).id();
        var states = new PostgresMcpOAuthStateRepository(jdbc);
        var service = new McpOAuthApplicationService(
                marketplace, states, cipher, new Metadata(), new OAuth(),
                new Registrations(), identity, mapper, ids, () -> NOW);

        var begin = service.begin(new McpOAuthApplicationApi.BeginCommand(
                tenant, user, connectionId, REDIRECT));
        assertThat(jdbc.queryForMap("""
                SELECT connection_revision, consumed_at, provider_session_redacted_at,
                       encrypted_provider_session
                  FROM platform_mcp_oauth_states
                 WHERE connection_id = CAST(? AS UUID)
                """, connectionId))
                .containsEntry("connection_revision", 1L)
                .containsEntry("consumed_at", null)
                .containsEntry("provider_session_redacted_at", null);

        var grant = service.complete(new McpOAuthApplicationApi.CompleteCommand(
                tenant, user, begin.state(), "authorization-code"));

        assertThat(grant.state()).isEqualTo(McpConnectionState.PENDING_VALIDATION);
        Map<String, Object> connection = jdbc.queryForMap("""
                SELECT state, revision, encrypted_auth_json
                  FROM platform_mcp_connections WHERE id = CAST(? AS UUID)
                """, connectionId);
        assertThat(connection)
                .containsEntry("state", "PENDING_VALIDATION")
                .containsEntry("revision", 2L);
        assertThat(connection.get("encrypted_auth_json").toString())
                .doesNotContain("access-token", "refresh-token");
        assertThat(jdbc.queryForMap("""
                SELECT consumed_at IS NOT NULL AS consumed,
                       provider_session_redacted_at IS NOT NULL AS redacted_at,
                       encrypted_provider_session
                  FROM platform_mcp_oauth_states
                 WHERE connection_id = CAST(? AS UUID)
                """, connectionId))
                .containsEntry("consumed", true)
                .containsEntry("redacted_at", true)
                .containsEntry("encrypted_provider_session", "REDACTED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(110L);

        new PostgresToolingCleanupService(jdbc).cleanupOrganization(tenant);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_oauth_states WHERE tenant_id=?",
                Long.class, tenant)).isZero();
    }

    @Test
    void v1041BackfillsRevisionAndRedactsPreviouslyConsumedTransactions() {
        String schema = "mcp_v1040_upgrade";
        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1040"))
                .load().migrate();
        DriverManagerDataSource scoped = schemaDataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(scoped);
        var ids = new UuidGenerator();
        var identity = new IdentityApplicationService(
                new PostgresIdentityRepository(jdbc), ids, () -> NOW);
        String tenant = identity.createTenant(
                new CreateTenantCommand("Upgrade", "oauth-upgrade")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "oauth-upgrade@example.com", "Upgrade")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var marketplace = new PostgresMcpMarketplaceRepository(jdbc);
        var catalog = new McpMarketplaceApplicationService(
                marketplace, identity, new Cipher(), new ObjectMapper(), ids, () -> NOW);
        var installation = catalog.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user,
                marketplace.findEntryBySlug("custom-streamable-http").orElseThrow().id(),
                McpInstallationScope.USER, null));
        String connectionId = catalog.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), "https://mcp.acme.example/mcp",
                McpAuthType.OAUTH2, Map.of())).id();
        jdbc.update("""
                INSERT INTO platform_mcp_oauth_states(
                    id, connection_id, tenant_id, user_id, state_hash,
                    encrypted_provider_session, expires_at, created_at, consumed_at)
                VALUES(CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?),
                      (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, NULL)
                """,
                UUID.randomUUID().toString(), connectionId, tenant, user, "a".repeat(64),
                "legacy-consumed-cipher", Timestamp.from(NOW.plusSeconds(600)),
                Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(1)),
                UUID.randomUUID().toString(), connectionId, tenant, user, "b".repeat(64),
                "legacy-pending-cipher", Timestamp.from(NOW.plusSeconds(600)),
                Timestamp.from(NOW));

        Flyway.configure().dataSource(schemaDataSource(schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime").load().migrate();

        assertThat(jdbc.queryForList("""
                SELECT connection_revision, encrypted_provider_session,
                       provider_session_redacted_at IS NOT NULL AS redacted
                  FROM platform_mcp_oauth_states ORDER BY state_hash
                """))
                .containsExactly(
                        Map.of("connection_revision", 1L,
                                "encrypted_provider_session", "REDACTED", "redacted", true),
                        Map.of("connection_revision", 1L,
                                "encrypted_provider_session", "legacy-pending-cipher",
                                "redacted", false));
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                 WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("1098");
    }

    private static final class Metadata implements McpOAuthMetadataGateway {
        @Override
        public OAuthServerMetadata resolve(
                McpConnection connection, Set<String> allowedAuthorizationServers) {
            return metadata();
        }
    }

    private static final class OAuth implements McpOAuthClientGateway {
        @Override
        public AuthorizationSession begin(
                String state, String redirectUri, McpOAuthClientRegistration registration,
                McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            return new AuthorizationSession(
                    metadata.authorizationEndpoint() + "?state=" + state,
                    redirectUri, "v".repeat(64), registration.scopes());
        }

        @Override
        public TokenGrant exchange(
                String state, String code, String redirectUri, String codeVerifier,
                McpOAuthClientRegistration registration,
                McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            return new TokenGrant(
                    "access-token", "refresh-token", "Bearer", registration.scopes(),
                    NOW, NOW.plusSeconds(3_600), NOW.plusSeconds(7_200));
        }

        @Override
        public TokenGrant refresh(
                String refreshToken, Set<String> scopes,
                McpOAuthClientRegistration registration,
                McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Registrations
            implements McpOAuthClientRegistrationProvider {
        private final McpOAuthClientRegistration value = registration();

        @Override
        public Set<String> authorizationServers() {
            return Set.of(value.authorizationServer());
        }

        @Override
        public Optional<McpOAuthClientRegistration> findByAuthorizationServer(String issuer) {
            return value.authorizationServer().equals(issuer) ? Optional.of(value) : Optional.empty();
        }

        @Override
        public Optional<McpOAuthClientRegistration> findById(String id) {
            return value.id().equals(id) ? Optional.of(value) : Optional.empty();
        }
    }

    private static McpOAuthClientRegistration registration() {
        return new McpOAuthClientRegistration(
                "acme", "https://login.acme.example/oauth", "spaceagent-client",
                "client-secret", McpOAuthClientAuthenticationMethod.CLIENT_SECRET_POST,
                Set.of("tools.read", "tools.call"), Set.of(REDIRECT));
    }

    private static McpOAuthMetadataGateway.OAuthServerMetadata metadata() {
        return new McpOAuthMetadataGateway.OAuthServerMetadata(
                "https://mcp.acme.example/mcp", "https://login.acme.example/oauth",
                "https://login.acme.example/oauth/authorize",
                "https://login.acme.example/oauth/token",
                Set.of("tools.read", "tools.call"), Set.of("client_secret_post"));
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DriverManagerDataSource schemaDataSource(String schema) {
        return com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema);
    }

    private static final class Cipher implements McpConnectionSecretCipher {
        @Override
        public String encrypt(String value) {
            return "cipher:" + Base64.getEncoder().encodeToString(
                    value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decrypt(String value) {
            return new String(Base64.getDecoder().decode(value.substring(7)),
                    StandardCharsets.UTF_8);
        }
    }
}
