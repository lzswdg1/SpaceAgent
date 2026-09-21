package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.tooling.api.GithubMcpCheckoutApplicationApi;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.application.GithubMcpCheckoutApplicationService;
import com.spaceagent.platform.tooling.application.McpConnectionAuthorizationService;
import com.spaceagent.platform.tooling.application.McpMarketplaceApplicationService;
import com.spaceagent.platform.tooling.domain.GithubMcpHostOAuthGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpCheckoutGrantRepository;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpInvocationLedgerRepository;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpMarketplaceRepository;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformGithubOfficialMcpCheckoutTest {
    private static final Instant NOW = Instant.parse("2026-08-24T04:00:00Z");

    @Test
    void derivesConsumesAndRedactsOfficialCheckoutLeaseWithoutRemoteCredentialTool() {
        var ids = new UuidGenerator();
        var identityRepository = new InMemoryIdentityRepository();
        var identity = new IdentityApplicationService(identityRepository, ids, () -> NOW);
        String tenant = identity.createTenant(new CreateTenantCommand("Checkout", "official")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "official-checkout@example.com", "Checkout")).id();
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
        String connection = marketplace.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), null, null, Map.of())).id();
        McpConnection current = repository.findConnection(connection).orElseThrow();
        String storedAuth;
        try {
            storedAuth = mapper.writeValueAsString(Map.of(
                    "oauth_profile", GithubMcpProfiles.OFFICIAL_PROFILE,
                    "access_token", "official-checkout-token",
                    "token_type", "Bearer"));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        repository.saveConnection(new McpConnection(
                current.id(), current.installationId(), current.tenantId(), current.managedBy(),
                current.endpointUrl(), cipher.encrypt(storedAuth), current.authType(),
                com.spaceagent.platform.tooling.domain.McpConnectionState.ACTIVE,
                "42", "octocat", current.revision() + 1,
                current.createdAt(), NOW, null));
        var ledger = new InMemoryMcpInvocationLedgerRepository(() -> NOW);
        var tools = new CountingTools();
        var authorization = new McpConnectionAuthorizationService(
                repository, cipher, new UnsupportedOAuth(), mapper, () -> NOW);
        var checkout = new GithubMcpCheckoutApplicationService(
                repository, ledger, ledger, tools, cipher, authorization, mapper,
                ids, () -> NOW);
        var command = new GithubMcpCheckoutApplicationApi.PrepareCommand(
                tenant, user, "00000000-0000-4000-8000-000000000911",
                "00000000-0000-4000-8000-000000000912", connection,
                "octocat/private-repo", "https://github.com/octocat/private-repo.git");

        var grant = checkout.prepare(command);
        var replay = checkout.prepare(command);
        String expectedHeader = "Basic " + Base64.getEncoder().encodeToString(
                "octocat:official-checkout-token".getBytes(StandardCharsets.UTF_8));
        assertThat(grant.authorizationHeader()).isEqualTo(expectedHeader);
        assertThat(replay.authorizationHeader()).isEqualTo(grant.authorizationHeader());
        assertThat(grant.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(tools.calls).hasValue(0);
        String encrypted = ledger.findAvailable(new McpCheckoutGrantRepository.GrantQuery(
                grant.grantId(), tenant, user)).orElseThrow().encryptedGrantJson();
        assertThat(encrypted).doesNotContain(
                "official-checkout-token", expectedHeader);

        checkout.consume(new GithubMcpCheckoutApplicationApi.ConsumeCommand(
                tenant, user, grant.grantId()));
        assertThat(ledger.findAvailable(new McpCheckoutGrantRepository.GrantQuery(
                grant.grantId(), tenant, user))).isEmpty();
    }

    private static final class CountingTools implements McpRemoteToolApplicationApi {
        private final AtomicInteger calls = new AtomicInteger();
        public List<ToolView> listTools(String tenantId, String userId, String connectionId) {
            return List.of();
        }
        public ToolResult callTool(CallCommand command) {
            calls.incrementAndGet();
            throw new AssertionError("official checkout must not call a credential-returning tool");
        }
    }

    private static final class UnsupportedOAuth implements GithubMcpHostOAuthGateway {
        public AuthorizationSession begin(String state, String redirectUri,
                com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }
        public TokenGrant exchange(
                String state, String code, String redirectUri, String codeVerifier,
                com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }
        public TokenGrant refresh(String refreshToken, Set<String> scopes,
                com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
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
