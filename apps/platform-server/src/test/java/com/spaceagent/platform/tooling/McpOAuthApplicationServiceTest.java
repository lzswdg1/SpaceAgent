package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
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
import com.spaceagent.platform.tooling.domain.McpOAuthState;
import com.spaceagent.platform.tooling.domain.McpOAuthStateRepository;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpMarketplaceRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpOAuthApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final String REDIRECT = "https://app.example/mcp/oauth/callback";

    @Test
    void storesEncryptedGrantAndRequiresQualificationAfterOneUseCallback() {
        Fixture fixture = fixture();

        var begin = fixture.service.begin(new McpOAuthApplicationApi.BeginCommand(
                fixture.tenant, fixture.user, fixture.connectionId, REDIRECT));
        assertThat(begin.authorizationUrl())
                .contains("https://login.acme.example/oauth/authorize")
                .doesNotContain("client-secret");

        var completed = fixture.service.complete(new McpOAuthApplicationApi.CompleteCommand(
                fixture.tenant, fixture.user, begin.state(), "authorization-code"));

        assertThat(completed.state()).isEqualTo(McpConnectionState.PENDING_VALIDATION);
        assertThat(completed.grantedScopes())
                .containsExactlyInAnyOrder("tools.read", "tools.call");
        McpConnection stored = fixture.marketplace.findConnection(fixture.connectionId).orElseThrow();
        assertThat(stored.revision()).isEqualTo(2);
        assertThat(stored.encryptedAuthJson())
                .doesNotContain("access-token", "refresh-token", "client-secret");
        assertThat(fixture.cipher.decrypt(stored.encryptedAuthJson()))
                .contains("generic-oauth-v1", "access-token", "refresh-token", "acme");
        assertThat(fixture.states.only().encryptedProviderSession()).isEqualTo("REDACTED");
        assertThatThrownBy(() -> fixture.service.complete(
                new McpOAuthApplicationApi.CompleteCommand(
                        fixture.tenant, fixture.user, begin.state(), "authorization-code")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MCP_OAUTH_STATE_INVALID"));
    }

    @Test
    void staleCallbackCannotOverwriteChangedConnectionAndTransactionIsRedacted() {
        Fixture fixture = fixture();
        var begin = fixture.service.begin(new McpOAuthApplicationApi.BeginCommand(
                fixture.tenant, fixture.user, fixture.connectionId, REDIRECT));
        McpConnection current = fixture.marketplace.findConnection(fixture.connectionId).orElseThrow();
        fixture.marketplace.saveConnection(new McpConnection(
                current.id(), current.installationId(), current.tenantId(), current.managedBy(),
                current.endpointUrl(), current.encryptedAuthJson(), current.authType(),
                current.state(), null, null, current.revision() + 1,
                current.createdAt(), NOW.plusSeconds(1), null));

        assertThatThrownBy(() -> fixture.service.complete(
                new McpOAuthApplicationApi.CompleteCommand(
                        fixture.tenant, fixture.user, begin.state(), "authorization-code")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_OAUTH_CONNECTION_CHANGED"));
        assertThat(fixture.states.only().encryptedProviderSession()).isEqualTo("REDACTED");
        assertThat(fixture.marketplace.findConnection(fixture.connectionId).orElseThrow()
                .revision()).isEqualTo(2);
    }

    @Test
    void providerDenialConsumesAndRedactsTheAuthorizationTransaction() {
        Fixture fixture = fixture();
        var begin = fixture.service.begin(new McpOAuthApplicationApi.BeginCommand(
                fixture.tenant, fixture.user, fixture.connectionId, REDIRECT));

        assertThatThrownBy(() -> fixture.service.complete(
                new McpOAuthApplicationApi.CompleteCommand(
                        fixture.tenant, fixture.user, begin.state(), null, "access_denied")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MCP_OAUTH_DENIED"));
        assertThat(fixture.states.only().encryptedProviderSession()).isEqualTo("REDACTED");
        assertThat(fixture.marketplace.findConnection(fixture.connectionId).orElseThrow().state())
                .isEqualTo(McpConnectionState.PENDING_AUTH);
    }

    private static Fixture fixture() {
        var ids = new UuidGenerator();
        var identity = new IdentityApplicationService(
                new InMemoryIdentityRepository(), ids, () -> NOW);
        String tenant = identity.createTenant(
                new CreateTenantCommand("Generic OAuth", "generic-oauth")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "oauth-generic@example.com", "Generic OAuth")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var marketplace = new InMemoryMcpMarketplaceRepository();
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
        var states = new RecordingStates();
        McpOAuthApplicationService service = new McpOAuthApplicationService(
                marketplace, states, cipher, new Metadata(), new OAuth(),
                new Registrations(), identity, mapper, ids, () -> NOW);
        return new Fixture(
                tenant, user, connectionId, marketplace, states, cipher, service);
    }

    private static final class Metadata implements McpOAuthMetadataGateway {
        @Override
        public OAuthServerMetadata resolve(
                McpConnection connection, Set<String> allowedAuthorizationServers) {
            assertThat(connection.state()).isEqualTo(McpConnectionState.PENDING_AUTH);
            assertThat(allowedAuthorizationServers)
                    .containsExactlyInAnyOrder("https://login.acme.example/oauth");
            return metadata();
        }
    }

    private static final class OAuth implements McpOAuthClientGateway {
        @Override
        public AuthorizationSession begin(
                String state, String redirectUri, McpOAuthClientRegistration registration,
                McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            assertThat(registration.id()).isEqualTo("acme");
            return new AuthorizationSession(
                    metadata.authorizationEndpoint() + "?state=" + state,
                    redirectUri, "v".repeat(64), registration.scopes());
        }

        @Override
        public TokenGrant exchange(
                String state, String code, String redirectUri, String codeVerifier,
                McpOAuthClientRegistration registration,
                McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            assertThat(code).isEqualTo("authorization-code");
            assertThat(codeVerifier).hasSize(64);
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
        private final McpOAuthClientRegistration registration = registration();

        @Override
        public Set<String> authorizationServers() {
            return Set.of(registration.authorizationServer());
        }

        @Override
        public Optional<McpOAuthClientRegistration> findByAuthorizationServer(String value) {
            return registration.authorizationServer().equals(value)
                    ? Optional.of(registration) : Optional.empty();
        }

        @Override
        public Optional<McpOAuthClientRegistration> findById(String value) {
            return registration.id().equals(value) ? Optional.of(registration) : Optional.empty();
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

    private static final class RecordingStates implements McpOAuthStateRepository {
        private final Map<String, McpOAuthState> values = new LinkedHashMap<>();

        @Override
        public void save(McpOAuthState state) {
            values.put(state.stateHash(), state);
        }

        @Override
        public Optional<McpOAuthState> consume(
                String stateHash, String tenantId, String userId, Instant now) {
            McpOAuthState state = values.get(stateHash);
            if (state == null || state.consumedAt() != null
                    || !state.tenantId().equals(tenantId) || !state.userId().equals(userId)
                    || !state.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            McpOAuthState consumed = new McpOAuthState(
                    state.id(), state.connectionId(), state.connectionRevision(),
                    state.tenantId(), state.userId(), state.stateHash(),
                    state.encryptedProviderSession(), state.expiresAt(), state.createdAt(), now);
            values.put(stateHash, consumed);
            return Optional.of(consumed);
        }

        @Override
        public void redact(String id, Instant now) {
            McpOAuthState state = only();
            values.put(state.stateHash(), new McpOAuthState(
                    state.id(), state.connectionId(), state.connectionRevision(),
                    state.tenantId(), state.userId(), state.stateHash(), "REDACTED",
                    state.expiresAt(), state.createdAt(), state.consumedAt()));
        }

        McpOAuthState only() {
            return values.values().iterator().next();
        }
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

    private record Fixture(
            String tenant,
            String user,
            String connectionId,
            InMemoryMcpMarketplaceRepository marketplace,
            RecordingStates states,
            Cipher cipher,
            McpOAuthApplicationService service) {
    }
}
