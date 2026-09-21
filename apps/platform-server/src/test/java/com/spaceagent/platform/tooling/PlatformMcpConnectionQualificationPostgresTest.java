package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.tooling.api.McpConnectionQualificationApplicationApi;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.application.McpConnectionAuthorizationService;
import com.spaceagent.platform.tooling.application.McpConnectionQualificationService;
import com.spaceagent.platform.tooling.application.McpMarketplaceApplicationService;
import com.spaceagent.platform.tooling.domain.GithubMcpHostOAuthGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnectionObservationOutcome;
import com.spaceagent.platform.tooling.domain.McpConnectionProbeGateway;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresMcpConnectionQualificationRepository;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresMcpMarketplaceRepository;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresToolingCleanupService;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.flywaydb.core.Flyway;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PlatformMcpConnectionQualificationPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-04T02:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("mcp_qualification")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(dataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
    }

    @Test
    void qualifiesPersistsHealthFencesStaleCompletionAndCascadesCleanup() {
        DriverManagerDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var ids = new UuidGenerator();
        var identity = new IdentityApplicationService(
                new PostgresIdentityRepository(jdbc), ids, () -> NOW);
        String tenant = identity.createTenant(
                new CreateTenantCommand("MCP Qualification", "mcp-qualification")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "qualification@example.com", "Qualification")).id();
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
        var configured = catalog.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), "https://mcp.example.test/mcp",
                McpAuthType.BEARER, Map.of("token", "postgres-fixture-secret")));
        assertThat(configured.state()).isEqualTo(McpConnectionState.PENDING_VALIDATION);

        var qualifications = new PostgresMcpConnectionQualificationRepository(
                jdbc, new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var authorization = new McpConnectionAuthorizationService(
                marketplace, cipher, new UnsupportedOAuth(), mapper, () -> NOW);
        var service = new McpConnectionQualificationService(
                marketplace, qualifications, authorization, successProbe(), identity,
                mapper, ids, () -> NOW);
        var result = service.qualify(new McpConnectionQualificationApplicationApi.QualifyCommand(
                tenant, user, configured.id()));

        assertThat(result.state()).isEqualTo(McpConnectionState.ACTIVE);
        assertThat(result.toolCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM platform_mcp_connections WHERE id = CAST(? AS UUID)
                """, String.class, configured.id())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT tools_json::text FROM platform_mcp_capability_snapshots
                 WHERE connection_id = CAST(? AS UUID)
                """, String.class, configured.id()))
                .contains("lookup")
                .doesNotContain("postgres-fixture-secret");
        assertThat(qualifications.findObservations(configured.id(), 10))
                .extracting(value -> value.outcome())
                .containsExactly(McpConnectionObservationOutcome.SUCCEEDED);

        McpConnectionProbeGateway staleProbe = (connection, auth) -> {
            jdbc.update("""
                    UPDATE platform_mcp_connections
                       SET revision = revision + 1, updated_at = clock_timestamp()
                     WHERE id = CAST(? AS UUID)
                    """, connection.id());
            return successProbe().probe(connection, auth);
        };
        var staleService = new McpConnectionQualificationService(
                marketplace, qualifications, authorization, staleProbe, identity,
                mapper, ids, () -> NOW.plusSeconds(1));
        assertThatThrownBy(() -> staleService.qualify(
                new McpConnectionQualificationApplicationApi.QualifyCommand(
                        tenant, user, configured.id())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MCP_CONNECTION_CHANGED"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_mcp_capability_snapshots
                 WHERE connection_id = CAST(? AS UUID)
                """, Long.class, configured.id())).isEqualTo(1L);

        var failingService = new McpConnectionQualificationService(
                marketplace, qualifications, authorization,
                (connection, auth) -> {
                    throw new IllegalStateException("private remote failure");
                }, identity, mapper, ids, () -> NOW.plusSeconds(2));
        assertThatThrownBy(() -> failingService.qualify(
                new McpConnectionQualificationApplicationApi.QualifyCommand(
                        tenant, user, configured.id())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_CONNECTION_QUALIFICATION_FAILED"));
        assertThat(jdbc.queryForMap("""
                SELECT state, last_error_code, consecutive_failures
                  FROM platform_mcp_connections WHERE id = CAST(? AS UUID)
                """, configured.id()))
                .containsEntry("state", "DEGRADED")
                .containsEntry("last_error_code", "MCP_CONNECTION_QUALIFICATION_FAILED")
                .containsEntry("consecutive_failures", 1);
        assertThat(qualifications.findObservations(configured.id(), 10)).hasSize(2);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(110L);
        new PostgresToolingCleanupService(jdbc).cleanupOrganization(tenant);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_capability_snapshots", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_connection_observations", Long.class)).isZero();
    }

    private static McpConnectionProbeGateway successProbe() {
        return (connection, auth) -> new McpConnectionProbeGateway.ProbeResult(
                "2025-11-25", "postgres-fixture", "PostgreSQL Fixture", "1.0.0", null,
                Map.of("tools", Map.of("listChanged", false)),
                List.of(new McpConnectionProbeGateway.ProbeTool(
                        "lookup", "Lookup", "Lookup fixture data",
                        Map.of("type", "object"), Map.of("type", "object"),
                        true, false, true, false)));
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static final class UnsupportedOAuth implements GithubMcpHostOAuthGateway {
        public AuthorizationSession begin(
                String state, String redirectUri,
                GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }

        public TokenGrant exchange(
                String state, String code, String redirectUri, String codeVerifier,
                GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }

        public TokenGrant refresh(
                String refreshToken, Set<String> scopes,
                GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }
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
