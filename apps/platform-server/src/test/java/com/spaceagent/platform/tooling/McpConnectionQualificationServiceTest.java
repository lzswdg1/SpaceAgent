package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.tooling.api.McpConnectionQualificationApplicationApi;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.application.McpConnectionAuthorizationService;
import com.spaceagent.platform.tooling.application.McpConnectionQualificationService;
import com.spaceagent.platform.tooling.application.McpMarketplaceApplicationService;
import com.spaceagent.platform.tooling.domain.GithubMcpHostOAuthGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionObservationOutcome;
import com.spaceagent.platform.tooling.domain.McpConnectionProbeGateway;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpConnectionQualificationRepository;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpMarketplaceRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpConnectionQualificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-04T01:00:00Z");

    @Test
    void activatesOnlyAfterProbeAndPersistsSafeSnapshotAndHealth() {
        Fixture fixture = fixture(false);
        assertThat(fixture.connection().state()).isEqualTo(McpConnectionState.PENDING_VALIDATION);

        var qualified = fixture.service(successProbe()).qualify(command(fixture));

        assertThat(qualified.state()).isEqualTo(McpConnectionState.ACTIVE);
        assertThat(qualified.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(qualified.serverName()).isEqualTo("fixture-mcp");
        assertThat(qualified.toolCount()).isEqualTo(1);
        assertThat(qualified.snapshotSha256()).matches("[0-9a-f]{64}");
        assertThat(qualified.lastObservation().outcome())
                .isEqualTo(McpConnectionObservationOutcome.SUCCEEDED);
        assertThat(fixture.qualifications.findCurrentSnapshot(fixture.connectionId))
                .get().satisfies(snapshot -> {
                    assertThat(snapshot.capabilitiesJson()).contains("tools");
                    assertThat(snapshot.toolsJson()).contains("lookup");
                    assertThat(snapshot.toolsJson()).doesNotContain("fixture-secret");
                });

        assertThatThrownBy(() -> fixture.service((connection, auth) -> {
                    throw new IllegalStateException("secret-shaped remote error");
                }).qualify(command(fixture)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_CONNECTION_QUALIFICATION_FAILED"));
        assertThat(fixture.connection().state()).isEqualTo(McpConnectionState.DEGRADED);
        assertThat(fixture.qualifications.findCurrentSnapshot(fixture.connectionId))
                .get().extracting(value -> value.snapshotSha256())
                .isEqualTo(qualified.snapshotSha256());
        assertThat(fixture.qualifications.findObservations(fixture.connectionId, 10))
                .extracting(value -> value.outcome())
                .containsExactly(
                        McpConnectionObservationOutcome.FAILED,
                        McpConnectionObservationOutcome.SUCCEEDED);
        assertThat(fixture.qualifications.findObservations(fixture.connectionId, 1)
                .getFirst().safeErrorCode()).isEqualTo("MCP_CONNECTION_QUALIFICATION_FAILED");
    }

    @Test
    void rejectsStaleProbeCompletionWhenConnectionRevisionChanges() {
        Fixture fixture = fixture(false);
        McpConnectionProbeGateway changingProbe = (connection, auth) -> {
            fixture.marketplace.saveConnection(new McpConnection(
                    connection.id(), connection.installationId(), connection.tenantId(),
                    connection.managedBy(), connection.endpointUrl(),
                    connection.encryptedAuthJson(), connection.authType(), connection.state(),
                    connection.externalAccountId(), connection.externalAccountName(),
                    connection.revision() + 1, connection.createdAt(), NOW.plusSeconds(1), null));
            return successProbe().probe(connection, auth);
        };

        assertThatThrownBy(() -> fixture.service(changingProbe).qualify(command(fixture)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MCP_CONNECTION_CHANGED"));
        assertThat(fixture.qualifications.findCurrentSnapshot(fixture.connectionId)).isEmpty();
        assertThat(fixture.qualifications.findObservations(fixture.connectionId, 10)).isEmpty();
    }

    @Test
    void oauthConnectionMustAuthorizeBeforeQualification() {
        Fixture fixture = fixture(true);
        assertThat(fixture.connection().state()).isEqualTo(McpConnectionState.PENDING_AUTH);
        assertThatThrownBy(() -> fixture.service(successProbe()).qualify(command(fixture)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MCP_STATE_CONFLICT"));
    }

    private static McpConnectionQualificationApplicationApi.QualifyCommand command(Fixture value) {
        return new McpConnectionQualificationApplicationApi.QualifyCommand(
                value.tenant, value.user, value.connectionId);
    }

    private static McpConnectionProbeGateway successProbe() {
        return (connection, auth) -> {
            assertThat(auth.values()).contains("fixture-secret");
            return new McpConnectionProbeGateway.ProbeResult(
                    "2025-11-25", "fixture-mcp", "Fixture MCP", "1.2.3",
                    "Fixture qualification server", Map.of("tools", Map.of("listChanged", true)),
                    List.of(new McpConnectionProbeGateway.ProbeTool(
                            "lookup", "Lookup", "Read one fixture value",
                            Map.of("type", "object"), Map.of("type", "object"),
                            true, false, true, false)));
        };
    }

    private static Fixture fixture(boolean oauthPending) {
        var ids = new UuidGenerator();
        var identity = new IdentityApplicationService(
                new InMemoryIdentityRepository(), ids, () -> NOW);
        String suffix = oauthPending ? "oauth" : "custom";
        String tenant = identity.createTenant(new CreateTenantCommand(
                "Qualification " + suffix, "qualification-" + suffix)).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, suffix + "@example.com", "Qualification")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var marketplace = new InMemoryMcpMarketplaceRepository();
        var cipher = new Cipher();
        var mapper = new ObjectMapper();
        var application = new McpMarketplaceApplicationService(
                marketplace, identity, cipher, mapper, ids, () -> NOW);
        String slug = oauthPending ? "github" : "custom-streamable-http";
        var installation = application.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, marketplace.findEntryBySlug(slug).orElseThrow().id(),
                McpInstallationScope.USER, null));
        var connection = application.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(),
                oauthPending ? null : "https://mcp.example.test/mcp",
                oauthPending ? McpAuthType.OAUTH2 : McpAuthType.BEARER,
                oauthPending ? Map.of() : Map.of("token", "fixture-secret")));
        var qualifications = new InMemoryMcpConnectionQualificationRepository(marketplace);
        return new Fixture(
                tenant, user, connection.id(), identity, marketplace, qualifications,
                cipher, mapper, ids);
    }

    private record Fixture(
            String tenant,
            String user,
            String connectionId,
            IdentityApplicationService identity,
            InMemoryMcpMarketplaceRepository marketplace,
            InMemoryMcpConnectionQualificationRepository qualifications,
            Cipher cipher,
            ObjectMapper mapper,
            UuidGenerator ids) {
        McpConnection connection() {
            return marketplace.findConnection(connectionId).orElseThrow();
        }

        McpConnectionQualificationService service(McpConnectionProbeGateway probe) {
            return new McpConnectionQualificationService(
                    marketplace, qualifications,
                    new McpConnectionAuthorizationService(
                            marketplace, cipher, new UnsupportedOAuth(), mapper, () -> NOW),
                    probe, identity, mapper, ids, () -> NOW);
        }
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
