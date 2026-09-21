package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.application.McpConnectionAuthorizationService;
import com.spaceagent.platform.tooling.application.McpOAuthApplicationService;
import com.spaceagent.platform.tooling.application.McpMarketplaceApplicationService;
import com.spaceagent.platform.tooling.domain.GithubMcpHostOAuthGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpConnectionObservation;
import com.spaceagent.platform.tooling.domain.McpConnectionObservationOutcome;
import com.spaceagent.platform.tooling.domain.McpCapabilitySnapshot;
import com.spaceagent.platform.tooling.domain.McpOAuthClientAuthenticationMethod;
import com.spaceagent.platform.tooling.domain.McpOAuthClientGateway;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistration;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistrationProvider;
import com.spaceagent.platform.tooling.domain.McpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpConnectionQualificationRepository;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpMarketplaceRepository;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpConnectionAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");

    @Test
    void refreshesExpiringTokenOnceWithRevisionCasAndKeepsSecretsEncrypted() {
        var ids = new UuidGenerator();
        var identityRepository = new InMemoryIdentityRepository();
        var identity = new IdentityApplicationService(identityRepository, ids, () -> NOW);
        String tenant = identity.createTenant(new CreateTenantCommand("Refresh", "refresh")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "refresh@example.com", "Refresh")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var repository = new InMemoryMcpMarketplaceRepository();
        var cipher = new Cipher();
        var mapper = new ObjectMapper();
        var marketplace = new McpMarketplaceApplicationService(
                repository, identity, cipher, mapper, ids, () -> NOW);
        var installation = marketplace.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, repository.findEntryBySlug("github").orElseThrow().id(),
                McpInstallationScope.USER, null));
        String connectionId = marketplace.connect(
                new McpMarketplaceApplicationApi.ConnectCommand(
                        tenant, user, installation.id(), null, null, Map.of())).id();
        var pending = repository.findConnection(connectionId).orElseThrow();
        String storedAuth;
        try {
            storedAuth = mapper.writeValueAsString(Map.ofEntries(
                    Map.entry("oauth_profile", GithubMcpProfiles.OFFICIAL_PROFILE),
                    Map.entry("access_token", "old-access-token"),
                    Map.entry("refresh_token", "old-refresh-token"),
                    Map.entry("token_type", "Bearer"),
                    Map.entry("scope", "repo read:user"),
                    Map.entry("oauth_resource", "https://api.githubcopilot.com/mcp"),
                    Map.entry("oauth_authorization_server", "https://github.com/login/oauth"),
                    Map.entry("oauth_authorization_endpoint", "https://github.com/login/oauth/authorize"),
                    Map.entry("oauth_token_endpoint", "https://github.com/login/oauth/access_token"),
                    Map.entry("oauth_scopes_supported", "repo read:user read:org"),
                    Map.entry("expires_at", NOW.plusSeconds(30).toString()),
                    Map.entry("refresh_token_expires_at", NOW.plusSeconds(3600).toString())));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        repository.saveConnection(new com.spaceagent.platform.tooling.domain.McpConnection(
                pending.id(), pending.installationId(), pending.tenantId(), pending.managedBy(),
                pending.endpointUrl(), cipher.encrypt(storedAuth), pending.authType(),
                com.spaceagent.platform.tooling.domain.McpConnectionState.ACTIVE,
                "42", "octocat", pending.revision() + 1, pending.createdAt(), NOW, null));
        var oauth = new RefreshOAuth();
        var service = new McpConnectionAuthorizationService(
                repository, cipher, oauth, mapper, () -> NOW);

        var first = service.authorize(repository.findConnection(connectionId).orElseThrow());
        var second = service.authorize(repository.findConnection(connectionId).orElseThrow());

        assertThat(first.authorization().get("access_token")).isEqualTo("new-access-token");
        assertThat(second.authorization().get("access_token")).isEqualTo("new-access-token");
        assertThat(first.connection().revision()).isEqualTo(2);
        assertThat(oauth.refreshes).hasValue(1);
        assertThat(repository.findConnection(connectionId).orElseThrow().encryptedAuthJson())
                .doesNotContain("old-access-token", "new-access-token", "new-refresh-token");
    }

    @Test
    void refreshesGenericGrantWithoutInvalidatingCapabilitySnapshot() throws Exception {
        var ids = new UuidGenerator();
        var identity = new IdentityApplicationService(
                new InMemoryIdentityRepository(), ids, () -> NOW);
        String tenant = identity.createTenant(new CreateTenantCommand("Generic", "generic-refresh")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "generic-refresh@example.com", "Generic")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var repository = new InMemoryMcpMarketplaceRepository();
        var cipher = new Cipher();
        var mapper = new ObjectMapper();
        var marketplace = new McpMarketplaceApplicationService(
                repository, identity, cipher, mapper, ids, () -> NOW);
        var installation = marketplace.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user,
                repository.findEntryBySlug("custom-streamable-http").orElseThrow().id(),
                McpInstallationScope.USER, null));
        String connectionId = marketplace.connect(
                new McpMarketplaceApplicationApi.ConnectCommand(
                        tenant, user, installation.id(), "https://mcp.acme.example/mcp",
                        com.spaceagent.platform.tooling.domain.McpAuthType.OAUTH2, Map.of())).id();
        var pending = repository.findConnection(connectionId).orElseThrow();
        String auth = mapper.writeValueAsString(Map.ofEntries(
                Map.entry("oauth_profile", McpOAuthApplicationService.PROFILE),
                Map.entry("oauth_client_registration_id", "acme"),
                Map.entry("access_token", "old-generic-access"),
                Map.entry("refresh_token", "old-generic-refresh"),
                Map.entry("token_type", "Bearer"),
                Map.entry("scope", "tools.read tools.call"),
                Map.entry("oauth_resource", "https://mcp.acme.example/mcp"),
                Map.entry("oauth_authorization_server", "https://login.acme.example/oauth"),
                Map.entry("oauth_authorization_endpoint",
                        "https://login.acme.example/oauth/authorize"),
                Map.entry("oauth_token_endpoint", "https://login.acme.example/oauth/token"),
                Map.entry("oauth_scopes_supported", "tools.read tools.call"),
                Map.entry("oauth_token_auth_methods", "client_secret_post"),
                Map.entry("expires_at", NOW.plusSeconds(30).toString()),
                Map.entry("refresh_token_expires_at", NOW.plusSeconds(3_600).toString())));
        var active = new com.spaceagent.platform.tooling.domain.McpConnection(
                pending.id(), pending.installationId(), pending.tenantId(), pending.managedBy(),
                pending.endpointUrl(), cipher.encrypt(auth), pending.authType(),
                com.spaceagent.platform.tooling.domain.McpConnectionState.ACTIVE,
                null, null, pending.revision() + 1, pending.createdAt(), NOW, null);
        repository.saveConnection(active);
        var qualifications = new InMemoryMcpConnectionQualificationRepository(repository);
        String snapshotId = ids.nextId();
        qualifications.completeSuccess(connectionId, active.revision(), new McpCapabilitySnapshot(
                        snapshotId, connectionId, active.revision(), "2025-11-25", "acme",
                        null, "1.0", null, "{}", "[]", "a".repeat(64), NOW),
                new McpConnectionObservation(
                        ids.nextId(), connectionId, active.revision(),
                        McpConnectionObservationOutcome.SUCCEEDED, 1,
                        "2025-11-25", snapshotId, null, NOW), NOW);
        var generic = new GenericRefreshOAuth();
        var service = new McpConnectionAuthorizationService(
                repository, cipher, new RefreshOAuth(), generic,
                new GenericRegistrations(), mapper, () -> NOW);

        var first = service.authorize(repository.findConnection(connectionId).orElseThrow());
        var second = service.authorize(repository.findConnection(connectionId).orElseThrow());

        assertThat(first.authorization().get("access_token")).isEqualTo("new-generic-access");
        assertThat(second.connection().revision()).isEqualTo(active.revision());
        assertThat(generic.refreshes).hasValue(1);
        assertThat(qualifications.findCurrentSnapshot(connectionId))
                .get().extracting(McpCapabilitySnapshot::id).isEqualTo(snapshotId);
    }

    private static final class RefreshOAuth implements GithubMcpHostOAuthGateway {
        private final AtomicInteger refreshes = new AtomicInteger();
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
            assertThat(refreshToken).isEqualTo("old-refresh-token");
            refreshes.incrementAndGet();
            return new TokenGrant(
                    "new-access-token", "new-refresh-token", "Bearer", scopes,
                    NOW, NOW.plusSeconds(3600), NOW.plusSeconds(7200));
        }
    }

    private static final class GenericRefreshOAuth implements McpOAuthClientGateway {
        private final AtomicInteger refreshes = new AtomicInteger();

        @Override
        public AuthorizationSession begin(
                String state, String redirectUri, McpOAuthClientRegistration registration,
                McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenGrant exchange(
                String state, String code, String redirectUri, String codeVerifier,
                McpOAuthClientRegistration registration,
                McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenGrant refresh(
                String refreshToken, Set<String> scopes,
                McpOAuthClientRegistration registration,
                McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            assertThat(refreshToken).isEqualTo("old-generic-refresh");
            refreshes.incrementAndGet();
            return new TokenGrant(
                    "new-generic-access", "new-generic-refresh", "Bearer", scopes,
                    NOW, NOW.plusSeconds(3_600), NOW.plusSeconds(7_200));
        }
    }

    private static final class GenericRegistrations
            implements McpOAuthClientRegistrationProvider {
        private final McpOAuthClientRegistration registration = new McpOAuthClientRegistration(
                "acme", "https://login.acme.example/oauth", "spaceagent-client",
                "client-secret", McpOAuthClientAuthenticationMethod.CLIENT_SECRET_POST,
                Set.of("tools.read", "tools.call"),
                Set.of("https://app.example/mcp/oauth/callback"));

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
