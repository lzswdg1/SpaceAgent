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
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.McpConnection;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformGithubOfficialMcpApplicationTest {
    private static final Instant NOW = Instant.parse("2026-08-24T02:00:00Z");
    private static final GithubMcpOAuthMetadataGateway.OAuthServerMetadata METADATA =
            new GithubMcpOAuthMetadataGateway.OAuthServerMetadata(
                    "https://api.githubcopilot.com/mcp",
                    "https://github.com/login/oauth",
                    "https://github.com/login/oauth/authorize",
                    "https://github.com/login/oauth/access_token",
                    Set.of("repo", "read:user", "read:org"));
    private InMemoryMcpMarketplaceRepository repository;
    private Cipher cipher;
    private GithubMcpApplicationService github;
    private FakeOAuth oauth;
    private OfficialTools tools;
    private String tenant;
    private String user;
    private String connection;

    @BeforeEach
    void setUp() {
        var ids = new UuidGenerator();
        var identityRepository = new InMemoryIdentityRepository();
        var identity = new IdentityApplicationService(identityRepository, ids, () -> NOW);
        tenant = identity.createTenant(new CreateTenantCommand("Official", "official-mcp")).id();
        user = identity.createUser(new CreateUserCommand(
                tenant, "official@example.com", "Official")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        repository = new InMemoryMcpMarketplaceRepository();
        cipher = new Cipher();
        var marketplace = new McpMarketplaceApplicationService(
                repository, identity, cipher, new ObjectMapper(), ids, () -> NOW);
        var installation = marketplace.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, repository.findEntryBySlug("github").orElseThrow().id(),
                McpInstallationScope.USER, null));
        connection = marketplace.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), null, null, Map.of())).id();
        tools = new OfficialTools();
        var remote = new OfficialRemoteGateway();
        var objectMapper = new ObjectMapper();
        oauth = new FakeOAuth();
        github = new GithubMcpApplicationService(
                repository, new InMemoryMcpOAuthStateRepository(), cipher, tools, oauth,
                value -> METADATA,
                new GithubOfficialMcpAdapter(tools, remote, objectMapper), objectMapper,
                ids, () -> NOW);
    }

    @Test
    void hostOAuthActivatesOfficialConnectionAndMapsTextTools() {
        McpConnection pending = repository.findConnection(connection).orElseThrow();
        assertThat(pending.endpointUrl()).isEqualTo(GithubMcpProfiles.OFFICIAL_REMOTE_ENDPOINT);
        assertThat(pending.state()).isEqualTo(McpConnectionState.PENDING_AUTH);

        var begin = github.beginOAuth(new GithubMcpApplicationApi.BeginOAuthCommand(
                tenant, user, connection, "https://app.example/mcp/github/callback"));
        assertThat(begin.authorizationUrl()).contains("code_challenge=challenge");
        var account = github.completeOAuth(new GithubMcpApplicationApi.CompleteOAuthCommand(
                tenant, user, begin.state(), "authorization-code"));

        assertThat(account.login()).isEqualTo("octocat");
        assertThat(oauth.exchangedCode).isEqualTo("authorization-code");
        McpConnection active = repository.findConnection(connection).orElseThrow();
        assertThat(active.state()).isEqualTo(McpConnectionState.ACTIVE);
        assertThat(active.externalAccountId()).isEqualTo("42");
        assertThat(active.encryptedAuthJson()).doesNotContain("official-access-token");

        assertThat(github.repositories(tenant, user, connection))
                .extracting(GithubMcpApplicationApi.RepositoryView::providerRepositoryId)
                .containsExactly("octocat/demo", "acme/private-repo");
        assertThat(github.discover(new GithubMcpApplicationApi.DiscoverCommand(
                tenant, user, connection, "https://github.com/openai/openai-java.git")))
                .extracting(GithubMcpApplicationApi.RepositoryView::providerRepositoryId)
                .isEqualTo("openai/openai-java");
        assertThat(github.searchRepositories(new GithubMcpApplicationApi.SearchCommand(
                tenant, user, connection, "runtime", 5)))
                .extracting(GithubMcpApplicationApi.RepositoryView::providerRepositoryId)
                .containsExactly("spaceagent/runtime");
        assertThatThrownBy(() -> github.completeOAuth(
                new GithubMcpApplicationApi.CompleteOAuthCommand(
                        tenant, user, begin.state(), "authorization-code")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void officialCallbackRequiresCodeAndConsumesState() {
        var begin = github.beginOAuth(new GithubMcpApplicationApi.BeginOAuthCommand(
                tenant, user, connection, "https://app.example/mcp/github/callback"));
        assertThatThrownBy(() -> github.completeOAuth(
                new GithubMcpApplicationApi.CompleteOAuthCommand(
                        tenant, user, begin.state(), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("GITHUB_MCP_INPUT_INVALID"));
        assertThatThrownBy(() -> github.completeOAuth(
                new GithubMcpApplicationApi.CompleteOAuthCommand(
                        tenant, user, begin.state(), "later-code")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("GITHUB_MCP_STATE_CONFLICT"));
    }

    @Test
    void repositoryListPreservesOwnedRowsWhenSupplementalPrivateSearchFails() {
        activate();
        tools.privateFailure = "403 Resource not accessible by integration";

        assertThat(github.repositories(tenant, user, connection))
                .extracting(GithubMcpApplicationApi.RepositoryView::providerRepositoryId)
                .containsExactly("octocat/demo");
        assertThat(tools.queries).containsExactly("user:octocat", "is:private");
    }

    @Test
    void repositoryListReturnsPrivateCollaboratorRowsWhenOwnerQueryIsRejected() {
        activate();
        tools.ownerFailure = "422 Validation Failed: listed user cannot be searched";

        assertThat(github.repositories(tenant, user, connection))
                .extracting(GithubMcpApplicationApi.RepositoryView::providerRepositoryId)
                .containsExactly("acme/private-repo");
        assertThat(tools.queries).containsExactly("user:octocat", "is:private");
    }

    @Test
    void repositoryListReturnsSecretSafeRemoteFailureCategories() {
        activate();
        tools.ownerFailure = "remote secret detail: insufficient scope; required scope repo";
        tools.privateFailure = tools.ownerFailure;
        assertThatThrownBy(() -> github.repositories(tenant, user, connection))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("GITHUB_MCP_REPOSITORY_SCOPE_REQUIRED");
                    assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(error.getMessage()).doesNotContain("remote secret detail");
                });

        tools.ownerFailure = "API rate limit exceeded";
        tools.privateFailure = tools.ownerFailure;
        assertThatThrownBy(() -> github.repositories(tenant, user, connection))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("GITHUB_MCP_REPOSITORY_RATE_LIMITED");
                    assertThat(error.getStatus())
                            .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
                });
    }

    @Test
    void repositoryListSeparatesMalformedSuccessFromRemoteRejection() {
        activate();
        tools.ownerMalformed = true;
        tools.privateMalformed = true;

        assertThatThrownBy(() -> github.repositories(tenant, user, connection))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("GITHUB_MCP_REPOSITORY_RESPONSE_INVALID"));
    }

    private void activate() {
        if (repository.findConnection(connection).orElseThrow().state() == McpConnectionState.ACTIVE) {
            return;
        }
        var begin = github.beginOAuth(new GithubMcpApplicationApi.BeginOAuthCommand(
                tenant, user, connection, "https://app.example/mcp/github/callback"));
        github.completeOAuth(new GithubMcpApplicationApi.CompleteOAuthCommand(
                tenant, user, begin.state(), "authorization-code"));
    }

    private static final class FakeOAuth implements GithubMcpHostOAuthGateway {
        private String exchangedCode;
        public AuthorizationSession begin(
                String state, String redirectUri,
                GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            return new AuthorizationSession(
                    "https://github.com/login/oauth/authorize?state=" + state
                            + "&code_challenge=challenge&code_challenge_method=S256",
                    redirectUri, "pkce-verifier", Set.of("repo", "read:user"));
        }
        public TokenGrant exchange(
                String state, String code, String redirectUri, String codeVerifier,
                GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            exchangedCode = code;
            return new TokenGrant(
                    "official-access-token", "official-refresh-token", "Bearer",
                    Set.of("repo", "read:user"), NOW, NOW.plusSeconds(3600),
                    NOW.plusSeconds(86400));
        }
        public TokenGrant refresh(
                String refreshToken, Set<String> scopes,
                GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class OfficialRemoteGateway implements McpRemoteToolGateway {
        public List<RemoteTool> listTools(McpConnection connection, Map<String, String> auth) {
            return List.of();
        }
        public RemoteResult callTool(
                McpConnection connection, Map<String, String> auth,
                String tool, Map<String, Object> arguments) {
            assertThat(tool).isEqualTo("get_me");
            assertThat(auth.get("access_token")).isEqualTo("official-access-token");
            return new RemoteResult(false, null, "{\"login\":\"octocat\",\"id\":42}");
        }
    }

    private static final class OfficialTools implements McpRemoteToolApplicationApi {
        private final java.util.ArrayList<String> queries = new java.util.ArrayList<>();
        private String ownerFailure;
        private String privateFailure;
        private boolean ownerMalformed;
        private boolean privateMalformed;

        public List<ToolView> listTools(String tenantId, String userId, String connectionId) {
            return List.of();
        }
        public ToolResult callTool(CallCommand command) {
            assertThat(command.toolName()).isEqualTo("search_repositories");
            String query = String.valueOf(command.arguments().get("query"));
            queries.add(query);
            if (query.equals("user:octocat")) {
                if (ownerFailure != null) return new ToolResult(true, null, ownerFailure);
                if (ownerMalformed) return new ToolResult(false, null, "{\"unexpected\":true}");
                return search(repository("octocat", "demo", false));
            }
            if (query.equals("is:private")) {
                if (privateFailure != null) return new ToolResult(true, null, privateFailure);
                if (privateMalformed) return new ToolResult(false, null, "{\"unexpected\":true}");
                return search(repository("acme", "private-repo", true));
            }
            if (query.contains("openai-java") && query.contains("user:openai")) {
                return search(repository("openai", "openai-java", false));
            }
            if (query.equals("runtime")) {
                return search(repository("spaceagent", "runtime", false));
            }
            return new ToolResult(false, null,
                    "{\"total_count\":0,\"incomplete_results\":false,\"items\":[]}");
        }
        private static ToolResult search(String item) {
            return new ToolResult(false, null,
                    "{\"total_count\":1,\"incomplete_results\":false,\"items\":["
                            + item + "]}");
        }
        private static String repository(String owner, String name, boolean privateRepository) {
            return "{\"id\":1,\"name\":\"" + name + "\",\"full_name\":\""
                    + owner + "/" + name + "\",\"html_url\":\"https://github.com/"
                    + owner + "/" + name + "\",\"private\":" + privateRepository
                    + ",\"archived\":false,\"default_branch\":\"main\"}";
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
