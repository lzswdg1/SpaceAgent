package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.tooling.api.GithubMcpApplicationApi;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.application.GithubMcpApplicationService;
import com.spaceagent.platform.tooling.application.GithubOfficialMcpAdapter;
import com.spaceagent.platform.tooling.application.McpMarketplaceApplicationService;
import com.spaceagent.platform.tooling.domain.GithubMcpHostOAuthGateway;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpRemoteToolGateway;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpMarketplaceRepository;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpOAuthStateRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformGithubMcpApplicationTest {
    private GithubMcpApplicationService github;
    private InMemoryMcpMarketplaceRepository repository;
    private String tenant;
    private String user;
    private String connection;

    @BeforeEach
    void setUp() {
        var ids = new UuidGenerator();
        var identityRepository = new InMemoryIdentityRepository();
        var identity = new IdentityApplicationService(
                identityRepository, ids, () -> Instant.parse("2026-08-23T16:00:00Z"));
        tenant = identity.createTenant(new CreateTenantCommand("GitHub", "github-mcp")).id();
        user = identity.createUser(new CreateUserCommand(
                tenant, "github@example.com", "GitHub")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        repository = new InMemoryMcpMarketplaceRepository();
        var cipher = new Cipher();
        var marketplace = new McpMarketplaceApplicationService(
                repository, identity, cipher, new ObjectMapper(), ids,
                () -> Instant.parse("2026-08-23T16:00:00Z"));
        var entry = repository.findEntryBySlug("github").orElseThrow();
        var installation = marketplace.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, entry.id(), McpInstallationScope.USER, null));
        connection = marketplace.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), "https://mcp.github.example/mcp",
                McpAuthType.OAUTH2, Map.of())).id();
        var tools = new FakeTools();
        var objectMapper = new ObjectMapper();
        var official = new GithubOfficialMcpAdapter(
                tools, new UnsupportedRemoteGateway(), objectMapper);
        github = new GithubMcpApplicationService(
                repository, new InMemoryMcpOAuthStateRepository(), cipher, tools,
                new UnsupportedOAuth(), value -> { throw new UnsupportedOperationException(); },
                official, objectMapper, ids,
                () -> Instant.parse("2026-08-23T16:00:00Z"));
    }

    @Test
    void oauthStateIsOneTimeAndBindsAccount() {
        var begin = github.beginOAuth(new GithubMcpApplicationApi.BeginOAuthCommand(
                tenant, user, connection, "https://app.example/oauth/callback"));
        assertTrue(begin.authorizationUrl().startsWith("https://github.com/"));
        var account = github.completeOAuth(new GithubMcpApplicationApi.CompleteOAuthCommand(
                tenant, user, begin.state(), null));
        assertEquals("octocat", account.login());
        assertEquals(McpConnectionState.ACTIVE,
                repository.findConnection(connection).orElseThrow().state());
        assertThrows(BusinessException.class, () -> github.completeOAuth(
                new GithubMcpApplicationApi.CompleteOAuthCommand(
                        tenant, user, begin.state(), null)));
    }

    @Test
    void listsAccountRepositoriesAndStrictlyDiscoversPublicUrl() {
        var begin = github.beginOAuth(new GithubMcpApplicationApi.BeginOAuthCommand(
                tenant, user, connection, "https://app.example/callback"));
        github.completeOAuth(new GithubMcpApplicationApi.CompleteOAuthCommand(
                tenant, user, begin.state(), null));
        assertEquals("demo", github.repositories(tenant, user, connection).getFirst().name());
        var found = github.discover(new GithubMcpApplicationApi.DiscoverCommand(
                tenant, user, connection, "https://github.com/openai/openai-java.git"));
        assertEquals("openai-java", found.name());
        assertEquals("runtime", github.searchRepositories(
                new GithubMcpApplicationApi.SearchCommand(
                        tenant, user, connection, "runtime", 5)).getFirst().name());
        assertThrows(IllegalArgumentException.class, () -> github.discover(
                new GithubMcpApplicationApi.DiscoverCommand(
                        tenant, user, connection, "https://evil.example/openai/repo")));
    }

    private static final class FakeTools implements McpRemoteToolApplicationApi {
        @Override
        public List<ToolView> listTools(String tenantId, String userId, String connectionId) {
            return List.of();
        }

        @Override
        public ToolResult callTool(CallCommand command) {
            return switch (command.toolName()) {
                case "github_begin_oauth" -> new ToolResult(false, Map.of(
                        "authorizationUrl", "https://github.com/login/oauth/authorize",
                        "sessionId", "provider-session"), "");
                case "github_complete_oauth" -> new ToolResult(false, Map.of(
                        "accountId", "42", "login", "octocat",
                        "accessToken", "mcp-token"), "");
                case "github_list_repositories" -> new ToolResult(false, Map.of(
                        "repositories", List.of(repository("octocat", "demo"))), "");
                case "github_resolve_repository" -> new ToolResult(
                        false, repository("openai", "openai-java"), "");
                case "github_search_repositories" -> new ToolResult(false, Map.of(
                        "repositories", List.of(repository("spaceagent", "runtime"))), "");
                default -> throw new IllegalArgumentException();
            };
        }

        private static Map<String, Object> repository(String owner, String name) {
            return Map.of(
                    "id", owner + "/" + name,
                    "owner", owner,
                    "name", name,
                    "htmlUrl", "https://github.com/" + owner + "/" + name,
                    "cloneUrl", "https://github.com/" + owner + "/" + name + ".git",
                    "defaultBranch", "main",
                    "private", false);
        }
    }

    private static final class UnsupportedRemoteGateway implements McpRemoteToolGateway {
        public List<RemoteTool> listTools(
                com.spaceagent.platform.tooling.domain.McpConnection connection,
                Map<String, String> auth) {
            throw new UnsupportedOperationException();
        }
        public RemoteResult callTool(
                com.spaceagent.platform.tooling.domain.McpConnection connection,
                Map<String, String> auth, String tool, Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
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
        public TokenGrant refresh(String refreshToken, java.util.Set<String> scopes,
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
