package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.tooling.api.*;
import com.spaceagent.platform.tooling.application.*;
import com.spaceagent.platform.tooling.domain.*;
import com.spaceagent.platform.tooling.infrastructure.memory.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformGithubMcpCheckoutApplicationTest {
    private static final Instant NOW = Instant.parse("2026-08-23T18:00:00Z");
    private static final String HEADER = "Basic dXNlcjpzaG9ydC1saXZlZC1zZWNyZXQ=";
    private String tenant;
    private String user;
    private String connection;
    private GithubMcpCheckoutApplicationService checkout;
    private InMemoryMcpInvocationLedgerRepository ledger;
    private FakeTools tools;

    @BeforeEach
    void setUp() {
        var ids = new UuidGenerator();
        var identityRepository = new InMemoryIdentityRepository();
        var identity = new IdentityApplicationService(identityRepository, ids, () -> NOW);
        tenant = identity.createTenant(new CreateTenantCommand("Checkout", "mcp-checkout")).id();
        user = identity.createUser(new CreateUserCommand(
                tenant, "checkout@example.com", "Checkout")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var marketplaceRepository = new InMemoryMcpMarketplaceRepository();
        var cipher = new Cipher();
        var marketplace = new McpMarketplaceApplicationService(
                marketplaceRepository, identity, cipher, new ObjectMapper(), ids, () -> NOW);
        var entry = marketplaceRepository.findEntryBySlug("github").orElseThrow();
        var installation = marketplace.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, entry.id(), McpInstallationScope.USER, null));
        connection = marketplace.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), "https://mcp.github.example/mcp",
                McpAuthType.BEARER, Map.of("token", "mcp-connection-secret"))).id();
        McpConnection configured = marketplaceRepository.findConnection(connection).orElseThrow();
        marketplaceRepository.saveConnection(new McpConnection(
                configured.id(), configured.installationId(), configured.tenantId(),
                configured.managedBy(), configured.endpointUrl(), configured.encryptedAuthJson(),
                configured.authType(), com.spaceagent.platform.tooling.domain.McpConnectionState.ACTIVE,
                configured.externalAccountId(), configured.externalAccountName(),
                configured.revision() + 1, configured.createdAt(), NOW, null));
        ledger = new InMemoryMcpInvocationLedgerRepository(() -> NOW);
        tools = new FakeTools();
        var objectMapper = new ObjectMapper();
        var authorization = new McpConnectionAuthorizationService(
                marketplaceRepository, cipher, new UnsupportedOAuth(), objectMapper, () -> NOW);
        checkout = new GithubMcpCheckoutApplicationService(
                marketplaceRepository, ledger, ledger, tools, cipher, authorization, objectMapper,
                ids, () -> NOW);
    }

    @Test
    void encryptsReplaysAndConsumesShortLivedGrant() {
        GithubMcpCheckoutApplicationApi.PrepareCommand command = command("901");
        var first = checkout.prepare(command);
        var replay = checkout.prepare(command);
        assertThat(first.authorizationHeader()).isEqualTo(HEADER);
        assertThat(replay.authorizationHeader()).isEqualTo(HEADER);
        assertThat(first.toString()).contains("<redacted>").doesNotContain(HEADER);
        assertThat(tools.calls).hasValue(1);
        String encrypted = ledger.findAvailable(new McpCheckoutGrantRepository.GrantQuery(
                first.grantId(), tenant, user)).orElseThrow().encryptedGrantJson();
        assertThat(encrypted).doesNotContain("short-lived-secret", HEADER);

        checkout.consume(new GithubMcpCheckoutApplicationApi.ConsumeCommand(
                tenant, user, first.grantId()));
        assertThat(ledger.findAvailable(new McpCheckoutGrantRepository.GrantQuery(
                first.grantId(), tenant, user))).isEmpty();
        assertThatThrownBy(() -> checkout.prepare(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_CHECKOUT_GRANT_UNAVAILABLE"));
        assertThat(tools.calls).hasValue(1);
    }

    @Test
    void unknownRemoteOutcomeIsNotBlindlyRetried() {
        tools.fail = true;
        var command = command("902");
        assertThatThrownBy(() -> checkout.prepare(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_CHECKOUT_OUTCOME_UNKNOWN"));
        tools.fail = false;
        assertThatThrownBy(() -> checkout.prepare(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_CHECKOUT_OUTCOME_UNKNOWN"));
        assertThat(tools.calls).hasValue(1);
    }

    @Test
    void rejectsMalformedGrantAndForeignTenantBeforeSecretUse() {
        tools.malformed = true;
        var command = command("903");
        assertThatThrownBy(() -> checkout.prepare(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_CHECKOUT_RESPONSE_INVALID"));
        assertThatThrownBy(() -> checkout.prepare(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_CHECKOUT_REMOTE_FAILED"));
        assertThat(tools.calls).hasValue(1);

        var foreign = new GithubMcpCheckoutApplicationApi.PrepareCommand(
                "foreign-tenant", user, "00000000-0000-4000-8000-000000000904",
                "00000000-0000-4000-8000-000000000801", connection, "repo-42",
                "https://github.com/openai/private-repo.git");
        assertThatThrownBy(() -> checkout.prepare(foreign))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("MCP_CHECKOUT_NOT_FOUND"));
        assertThat(tools.calls).hasValue(1);
    }

    private GithubMcpCheckoutApplicationApi.PrepareCommand command(String suffix) {
        return new GithubMcpCheckoutApplicationApi.PrepareCommand(
                tenant, user, "00000000-0000-4000-8000-000000000" + suffix,
                "00000000-0000-4000-8000-000000000801", connection, "repo-42",
                "https://github.com/openai/private-repo.git");
    }

    private final class FakeTools implements McpRemoteToolApplicationApi {
        private final AtomicInteger calls = new AtomicInteger();
        private boolean fail;
        private boolean malformed;

        @Override
        public List<ToolView> listTools(String tenantId, String userId, String connectionId) {
            return List.of();
        }

        @Override
        public ToolResult callTool(CallCommand command) {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("timeout");
            if (malformed) return new ToolResult(false, Map.of(
                    "cloneUrl", "https://github.com/openai/private-repo.git",
                    "authorizationHeader", "Bearer unsafe\nheader",
                    "expiresAt", NOW.plusSeconds(60).toString()), "");
            return new ToolResult(false, Map.of(
                    "cloneUrl", "https://github.com/openai/private-repo.git",
                    "authorizationHeader", HEADER,
                    "expiresAt", NOW.plusSeconds(300).toString()), "");
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

    private static final class UnsupportedOAuth implements GithubMcpHostOAuthGateway {
        public AuthorizationSession begin(String state, String redirectUri,
                GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }
        public TokenGrant exchange(
                String state, String code, String redirectUri, String codeVerifier,
                GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }
        public TokenGrant refresh(String refreshToken, java.util.Set<String> scopes,
                GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }
    }
}
