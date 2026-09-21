package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.GithubMcpApplicationApi;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.domain.GithubMcpHostOAuthGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpOAuthState;
import com.spaceagent.platform.tooling.domain.McpOAuthStateRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GithubMcpApplicationService implements GithubMcpApplicationApi {
    private final McpMarketplaceRepository marketplace;
    private final McpOAuthStateRepository states;
    private final McpConnectionSecretCipher cipher;
    private final McpRemoteToolApplicationApi tools;
    private final GithubMcpHostOAuthGateway oauth;
    private final GithubMcpOAuthMetadataGateway oauthMetadata;
    private final GithubOfficialMcpAdapter official;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final SecureRandom random = new SecureRandom();

    public GithubMcpApplicationService(
            McpMarketplaceRepository marketplace,
            McpOAuthStateRepository states,
            McpConnectionSecretCipher cipher,
            McpRemoteToolApplicationApi tools,
            GithubMcpHostOAuthGateway oauth,
            GithubMcpOAuthMetadataGateway oauthMetadata,
            GithubOfficialMcpAdapter official,
            ObjectMapper json,
            IdGenerator ids,
            TimeProvider time) {
        this.marketplace = marketplace;
        this.states = states;
        this.cipher = cipher;
        this.tools = tools;
        this.oauth = oauth;
        this.oauthMetadata = oauthMetadata;
        this.official = official;
        this.json = json;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public OAuthView beginOAuth(BeginOAuthCommand command) {
        McpConnection connection = github(
                command.tenantId(), command.userId(), command.connectionId(), true);
        String rawState = token();
        String authorizationUrl;
        String providerSession;
        if (GithubMcpProfiles.isOfficialRemote(connection)) {
            GithubMcpHostOAuthGateway.AuthorizationSession session;
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata;
            try {
                metadata = oauthMetadata.resolve(connection);
                session = oauth.begin(rawState, command.redirectUri(), metadata);
            } catch (IllegalArgumentException error) {
                if (error.getMessage() != null && error.getMessage().contains("redirect")) {
                    throw new BusinessException(
                            "GitHub MCP redirect URI is invalid", HttpStatus.BAD_REQUEST,
                            "GITHUB_MCP_REDIRECT_INVALID");
                }
                throw oauthUnavailable();
            } catch (RuntimeException error) {
                throw oauthUnavailable();
            }
            authorizationUrl = session.authorizationUrl();
            providerSession = write(new OAuthTransaction(
                    GithubMcpProfiles.OFFICIAL_PROFILE, session.redirectUri(),
                    session.codeVerifier(), metadata.resource(),
                    metadata.authorizationServer(), metadata.authorizationEndpoint(),
                    metadata.tokenEndpoint(), metadata.scopesSupported()));
        } else {
            String redirect = https(command.redirectUri(), "redirectUri");
            McpRemoteToolApplicationApi.ToolResult result = tools.callTool(
                    new McpRemoteToolApplicationApi.CallCommand(
                            command.tenantId(), command.userId(), connection.id(),
                            "github_begin_oauth", Map.of("redirectUri", redirect)));
            Map<String, Object> data = map(result.structuredContent());
            authorizationUrl = https(text(data, "authorizationUrl"), "authorizationUrl");
            providerSession = text(data, "sessionId");
        }
        Instant now = time.now();
        Instant expiresAt = now.plusSeconds(600);
        states.save(new McpOAuthState(
                ids.nextId(), connection.id(), connection.revision(),
                command.tenantId(), command.userId(),
                hash(rawState), cipher.encrypt(providerSession), expiresAt, now, null));
        return new OAuthView(rawState, authorizationUrl, expiresAt);
    }

    @Override
    public AccountView completeOAuth(CompleteOAuthCommand command) {
        Instant now = time.now();
        McpOAuthState state = states.consume(
                        hash(command.state()), command.tenantId(), command.userId(), now)
                .orElseThrow(() -> conflict(
                        "GitHub MCP OAuth state is invalid, expired, or consumed"));
        try {
            McpConnection connection = github(
                    command.tenantId(), command.userId(), state.connectionId(), true);
            if (connection.revision() != state.connectionRevision()) {
                throw conflict("GitHub MCP connection changed during authorization");
            }
            if (GithubMcpProfiles.isOfficialRemote(connection)) {
                return completeOfficial(command, state, connection, now);
            }
            return completeCustom(command, state, connection, now);
        } finally {
            states.redact(state.id(), time.now());
        }
    }

    private AccountView completeOfficial(
            CompleteOAuthCommand command,
            McpOAuthState state,
            McpConnection connection,
            Instant now) {
        OAuthTransaction transaction = readTransaction(
                cipher.decrypt(state.encryptedProviderSession()));
        if (!GithubMcpProfiles.OFFICIAL_PROFILE.equals(transaction.profile())) {
            throw conflict("GitHub MCP OAuth profile changed during authorization");
        }
        GithubMcpHostOAuthGateway.TokenGrant grant;
        GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata = metadata(transaction);
        try {
            grant = oauth.exchange(
                    command.state(), required(command.code(), "code"),
                    transaction.redirectUri(), transaction.codeVerifier(), metadata);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException(
                    "GitHub MCP OAuth code exchange failed", HttpStatus.BAD_GATEWAY,
                    "GITHUB_MCP_OAUTH_EXCHANGE_FAILED");
        }
        Map<String, String> authorization = tokenAuthorization(grant, metadata);
        GithubOfficialMcpAdapter.Account account;
        try {
            account = official.account(connection, authorization);
        } catch (RuntimeException error) {
            throw new BusinessException(
                    "GitHub MCP account verification failed", HttpStatus.BAD_GATEWAY,
                    "GITHUB_MCP_ACCOUNT_VERIFICATION_FAILED");
        }
        McpConnection updated = active(
                connection, command.userId(), authorization,
                account.accountId(), account.login(), now);
        if (!marketplace.saveConnectionIfRevision(updated, connection.revision())) {
            throw conflict("GitHub MCP connection changed during authorization");
        }
        return new AccountView(updated.id(), account.accountId(), account.login());
    }

    private AccountView completeCustom(
            CompleteOAuthCommand command,
            McpOAuthState state,
            McpConnection connection,
            Instant now) {
        String providerSession = cipher.decrypt(state.encryptedProviderSession());
        McpRemoteToolApplicationApi.ToolResult result = tools.callTool(
                new McpRemoteToolApplicationApi.CallCommand(
                        command.tenantId(), command.userId(), connection.id(),
                        "github_complete_oauth", Map.of("sessionId", providerSession)));
        Map<String, Object> data = map(result.structuredContent());
        String accountId = text(data, "accountId");
        String login = text(data, "login");
        Map<String, String> merged = new LinkedHashMap<>(auth(connection));
        merged.put("github_session", providerSession);
        String accessToken = optional(data, "accessToken");
        if (accessToken != null) merged.put("access_token", accessToken);
        McpConnection updated = active(
                connection, command.userId(), merged, accountId, login, now);
        if (!marketplace.saveConnectionIfRevision(updated, connection.revision())) {
            throw conflict("GitHub MCP connection changed during authorization");
        }
        return new AccountView(updated.id(), accountId, login);
    }

    @Override
    public List<RepositoryView> repositories(
            String tenantId, String userId, String connectionId) {
        McpConnection connection = github(tenantId, userId, connectionId, false);
        requireActive(connection);
        if (GithubMcpProfiles.isOfficialRemote(connection)) {
            try {
                return official.repositories(tenantId, userId, connection);
            } catch (BusinessException error) {
                throw error;
            } catch (RuntimeException error) {
                throw new BusinessException(
                        "GitHub MCP repository listing failed", HttpStatus.BAD_GATEWAY,
                        "GITHUB_MCP_REPOSITORY_LIST_FAILED");
            }
        }
        McpRemoteToolApplicationApi.ToolResult result = tools.callTool(
                new McpRemoteToolApplicationApi.CallCommand(
                        tenantId, userId, connectionId,
                        "github_list_repositories", Map.of("limit", 100)));
        Object raw = map(result.structuredContent()).get("repositories");
        List<Map<String, Object>> rows = json.convertValue(
                raw == null ? List.of() : raw,
                new TypeReference<List<Map<String, Object>>>() { });
        if (rows.size() > 100) {
            throw new IllegalStateException("GitHub MCP repository page exceeds limit");
        }
        return rows.stream().map(this::repository).toList();
    }

    @Override
    public RepositoryView discover(DiscoverCommand command) {
        McpConnection connection = github(
                command.tenantId(), command.userId(), command.connectionId(), false);
        String url = githubUrl(command.githubUrl());
        if (GithubMcpProfiles.isOfficialRemote(connection)) {
            String[] parts = URI.create(url).getPath().substring(1).split("/", -1);
            try {
                return official.repository(
                        command.tenantId(), command.userId(), connection, parts[0], parts[1]);
            } catch (BusinessException error) {
                throw error;
            } catch (RuntimeException error) {
                throw new BusinessException(
                        "GitHub MCP repository discovery failed", HttpStatus.BAD_GATEWAY,
                        "GITHUB_MCP_REPOSITORY_DISCOVERY_FAILED");
            }
        }
        McpRemoteToolApplicationApi.ToolResult result = tools.callTool(
                new McpRemoteToolApplicationApi.CallCommand(
                        command.tenantId(), command.userId(), connection.id(),
                        "github_resolve_repository", Map.of("url", url)));
        return repository(map(result.structuredContent()));
    }

    @Override
    public List<RepositoryView> searchRepositories(SearchCommand command) {
        McpConnection connection = github(
                command.tenantId(), command.userId(), command.connectionId(), false);
        requireActive(connection);
        int limit = Math.max(1, Math.min(command.limit(), 20));
        if (GithubMcpProfiles.isOfficialRemote(connection)) {
            try {
                return official.searchRepositories(
                        command.tenantId(), command.userId(), connection,
                        command.query(), limit);
            } catch (BusinessException error) {
                throw error;
            } catch (RuntimeException error) {
                throw new BusinessException(
                        "GitHub MCP repository search failed", HttpStatus.BAD_GATEWAY,
                        "GITHUB_MCP_REPOSITORY_SEARCH_FAILED");
            }
        }
        McpRemoteToolApplicationApi.ToolResult result = tools.callTool(
                new McpRemoteToolApplicationApi.CallCommand(
                        command.tenantId(), command.userId(), connection.id(),
                        "github_search_repositories",
                        Map.of("query", required(command.query(), "query"), "limit", limit)));
        Object raw = map(result.structuredContent()).get("repositories");
        List<Map<String, Object>> rows = json.convertValue(
                raw == null ? List.of() : raw,
                new TypeReference<List<Map<String, Object>>>() { });
        if (rows.size() > limit) rows = rows.subList(0, limit);
        return rows.stream().map(this::repository).toList();
    }

    private McpConnection active(
            McpConnection connection,
            String userId,
            Map<String, String> authorization,
            String accountId,
            String login,
            Instant now) {
        return new McpConnection(
                connection.id(), connection.installationId(), connection.tenantId(), userId,
                connection.endpointUrl(), cipher.encrypt(write(authorization)),
                connection.authType(), McpConnectionState.ACTIVE,
                accountId, login, connection.revision() + 1,
                connection.createdAt(), now, null);
    }

    private Map<String, String> tokenAuthorization(
            GithubMcpHostOAuthGateway.TokenGrant grant,
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("oauth_profile", GithubMcpProfiles.OFFICIAL_PROFILE);
        value.put("access_token", required(grant.accessToken(), "accessToken"));
        value.put("token_type", grant.tokenType() == null ? "Bearer" : grant.tokenType());
        value.put("oauth_resource", metadata.resource());
        value.put("oauth_authorization_server", metadata.authorizationServer());
        value.put("oauth_authorization_endpoint", metadata.authorizationEndpoint());
        value.put("oauth_token_endpoint", metadata.tokenEndpoint());
        value.put("oauth_scopes_supported", String.join(" ", metadata.scopesSupported()));
        if (grant.refreshToken() != null && !grant.refreshToken().isBlank()) {
            value.put("refresh_token", grant.refreshToken());
        }
        if (grant.scopes() != null && !grant.scopes().isEmpty()) {
            value.put("scope", String.join(" ", grant.scopes()));
        }
        putInstant(value, "issued_at", grant.issuedAt());
        putInstant(value, "expires_at", grant.expiresAt());
        putInstant(value, "refresh_token_expires_at", grant.refreshTokenExpiresAt());
        return value;
    }

    private static void putInstant(Map<String, String> value, String key, Instant instant) {
        if (instant != null) value.put(key, instant.toString());
    }

    private McpConnection github(
            String tenantId, String userId, String connectionId, boolean pendingAllowed) {
        McpConnection connection = marketplace.findConnections(tenantId, userId).stream()
                .filter(value -> value.id().equals(connectionId))
                .findFirst().orElseThrow(GithubMcpApplicationService::notFound);
        var installation = marketplace.findInstallation(connection.installationId())
                .orElseThrow(GithubMcpApplicationService::notFound);
        marketplace.findEntry(installation.entryId())
                .filter(value -> value.slug().equals("github"))
                .orElseThrow(GithubMcpApplicationService::notFound);
        if (connection.state() == McpConnectionState.REVOKED
                || (!pendingAllowed && connection.state() != McpConnectionState.ACTIVE)) {
            throw conflict("GitHub MCP account is not active");
        }
        return connection;
    }

    private void requireActive(McpConnection connection) {
        if (connection.state() != McpConnectionState.ACTIVE) {
            throw conflict("GitHub MCP account is not active");
        }
    }

    private RepositoryView repository(Map<String, Object> data) {
        String owner = identifier(text(data, "owner"), "owner");
        String name = identifier(text(data, "name"), "name");
        String html = repositoryUrl(text(data, "htmlUrl"), owner, name, false);
        String clone = repositoryUrl(text(data, "cloneUrl"), owner, name, true);
        return new RepositoryView(
                text(data, "id"), owner, name, html, clone,
                branch(text(data, "defaultBranch")),
                Boolean.TRUE.equals(data.get("private")),
                Boolean.TRUE.equals(data.get("archived")));
    }

    private Map<String, Object> map(Object value) {
        if (value == null) {
            throw new IllegalStateException("GitHub MCP returned no structured content");
        }
        return json.convertValue(value, new TypeReference<Map<String, Object>>() { });
    }

    private Map<String, String> auth(McpConnection connection) {
        try {
            return json.readValue(
                    cipher.decrypt(connection.encryptedAuthJson()),
                    new TypeReference<Map<String, String>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read GitHub MCP authorization");
        }
    }

    private OAuthTransaction readTransaction(String value) {
        try {
            return json.readValue(value, OAuthTransaction.class);
        } catch (Exception error) {
            throw conflict("GitHub MCP OAuth transaction is invalid");
        }
    }

    private GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata(
            OAuthTransaction transaction) {
        return new GithubMcpOAuthMetadataGateway.OAuthServerMetadata(
                required(transaction.resource(), "resource"),
                required(transaction.authorizationServer(), "authorizationServer"),
                required(transaction.authorizationEndpoint(), "authorizationEndpoint"),
                required(transaction.tokenEndpoint(), "tokenEndpoint"),
                transaction.scopesSupported() == null
                        ? Set.of() : Set.copyOf(transaction.scopesSupported()));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize GitHub MCP state");
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(required(value, "state").getBytes(StandardCharsets.UTF_8)));
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String text(Map<String, Object> data, String field) {
        String value = optional(data, field);
        if (value == null || value.isBlank() || value.length() > 1000) {
            throw new IllegalStateException("GitHub MCP response missing " + field);
        }
        return value;
    }

    private static String optional(Map<String, Object> data, String field) {
        Object value = data.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 4096
                || normalized.contains("\r") || normalized.contains("\n")) {
            throw new BusinessException(
                    field + " is invalid", HttpStatus.BAD_REQUEST,
                    "GITHUB_MCP_INPUT_INVALID");
        }
        return normalized;
    }

    private static String https(String value, String field) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) throw new IllegalArgumentException();
            return uri.normalize().toString();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " must be HTTPS");
        }
    }

    private static String identifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 100
                || !normalized.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalStateException("GitHub MCP returned invalid " + field);
        }
        return normalized;
    }

    private static String branch(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 255 || normalized.contains("..")
                || normalized.contains(" ") || normalized.contains("~")
                || normalized.contains("^") || normalized.contains(":")) {
            throw new IllegalStateException("GitHub MCP returned invalid defaultBranch");
        }
        return normalized;
    }

    private static String repositoryUrl(
            String value, String owner, String name, boolean clone) {
        String expected = "https://github.com/" + owner + "/" + name
                + (clone ? ".git" : "");
        if (!expected.equalsIgnoreCase(value)) {
            throw new IllegalStateException("GitHub MCP returned invalid repository URL");
        }
        return expected;
    }

    private static String githubUrl(String value) {
        String url = https(value, "githubUrl");
        URI uri = URI.create(url);
        if (!"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("githubUrl must be a repository URL");
        }
        String[] parts = uri.getPath().replaceAll("^/+|/+$", "").split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("githubUrl must identify owner/repository");
        }
        String repository = parts[1].endsWith(".git")
                ? parts[1].substring(0, parts[1].length() - 4) : parts[1];
        return "https://github.com/" + identifier(parts[0], "owner")
                + "/" + identifier(repository, "name");
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "GitHub MCP connection not found", HttpStatus.NOT_FOUND,
                "GITHUB_MCP_NOT_FOUND");
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(
                message, HttpStatus.CONFLICT, "GITHUB_MCP_STATE_CONFLICT");
    }

    private static BusinessException oauthUnavailable() {
        return new BusinessException(
                "GitHub MCP OAuth discovery or client configuration is unavailable",
                HttpStatus.SERVICE_UNAVAILABLE,
                "GITHUB_MCP_OAUTH_UNAVAILABLE");
    }

    private record OAuthTransaction(
            String profile,
            String redirectUri,
            String codeVerifier,
            String resource,
            String authorizationServer,
            String authorizationEndpoint,
            String tokenEndpoint,
            Set<String> scopesSupported) {
    }
}
