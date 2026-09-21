package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.McpOAuthClientAuthenticationMethod;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistration;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistrationProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ConfiguredMcpOAuthClientRegistrationProvider
        implements McpOAuthClientRegistrationProvider {
    private final Map<String, McpOAuthClientRegistration> byId;
    private final Map<String, McpOAuthClientRegistration> byAuthorizationServer;

    public ConfiguredMcpOAuthClientRegistrationProvider(McpToolingProperties properties) {
        Map<String, McpOAuthClientRegistration> ids = new LinkedHashMap<>();
        Map<String, McpOAuthClientRegistration> servers = new LinkedHashMap<>();
        for (McpToolingProperties.Client configured : properties.getOauth().getClients()) {
            if (empty(configured)) continue;
            McpOAuthClientRegistration registration = registration(configured);
            if (ids.putIfAbsent(registration.id(), registration) != null) {
                throw new IllegalStateException("Duplicate MCP OAuth client registration id");
            }
            if (servers.putIfAbsent(registration.authorizationServer(), registration) != null) {
                throw new IllegalStateException(
                        "Duplicate MCP OAuth authorization server registration");
            }
        }
        byId = Map.copyOf(ids);
        byAuthorizationServer = Map.copyOf(servers);
    }

    @Override
    public Set<String> authorizationServers() {
        return byAuthorizationServer.keySet();
    }

    @Override
    public Optional<McpOAuthClientRegistration> findByAuthorizationServer(
            String authorizationServer) {
        return Optional.ofNullable(byAuthorizationServer.get(
                authorizationServer(authorizationServer)));
    }

    @Override
    public Optional<McpOAuthClientRegistration> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    private static McpOAuthClientRegistration registration(
            McpToolingProperties.Client configured) {
        String id = oneLine(configured.getId(), "registration id", 80);
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalStateException("MCP OAuth registration id is invalid");
        }
        String authorizationServer = authorizationServer(configured.getAuthorizationServer());
        String clientId = oneLine(configured.getClientId(), "client id", 2_000);
        McpOAuthClientAuthenticationMethod authenticationMethod;
        try {
            authenticationMethod = McpOAuthClientAuthenticationMethod.valueOf(
                    oneLine(configured.getAuthenticationMethod(),
                            "client authentication method", 40)
                            .replace('-', '_').toUpperCase());
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "MCP OAuth client authentication method is unsupported");
        }
        String clientSecret = optional(configured.getClientSecret(), 4_096);
        if (authenticationMethod != McpOAuthClientAuthenticationMethod.NONE
                && clientSecret == null) {
            throw new IllegalStateException("MCP OAuth client secret is required");
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String configuredScope : configured.getScopes()) {
            if (blank(configuredScope)) continue;
            String scope = oneLine(configuredScope, "scope", 100);
            if (!scope.matches("[A-Za-z0-9:._/-]+")) {
                throw new IllegalStateException("MCP OAuth scope is invalid");
            }
            scopes.add(scope);
        }
        Set<String> redirects = new LinkedHashSet<>();
        for (String value : configured.getAllowedRedirectUris()) {
            if (blank(value)) continue;
            redirects.add(redirectUri(value));
        }
        if (redirects.isEmpty()) {
            throw new IllegalStateException("MCP OAuth redirect allowlist is empty");
        }
        return new McpOAuthClientRegistration(
                id, authorizationServer, clientId, clientSecret, authenticationMethod,
                scopes, redirects);
    }

    private static boolean empty(McpToolingProperties.Client value) {
        return blank(value.getId()) && blank(value.getAuthorizationServer())
                && blank(value.getClientId()) && blank(value.getClientSecret())
                && value.getScopes().stream().allMatch(
                        ConfiguredMcpOAuthClientRegistrationProvider::blank)
                && value.getAllowedRedirectUris().stream().allMatch(
                        ConfiguredMcpOAuthClientRegistrationProvider::blank);
    }

    static String authorizationServer(String value) {
        try {
            URI uri = URI.create(oneLine(value, "authorization server", 2_000)).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return trimTrailingSlash(uri.toString());
        } catch (RuntimeException error) {
            throw new IllegalStateException("MCP OAuth authorization server must be HTTPS");
        }
    }

    static String redirectUri(String value) {
        try {
            URI uri = URI.create(oneLine(value, "redirect URI", 2_000)).normalize();
            boolean secure = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
            boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost()))
                    && uri.getPort() > 0;
            if ((!secure && !loopback) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "MCP OAuth redirect URI must use HTTPS or explicit loopback HTTP");
        }
    }

    private static String oneLine(String value, String field, int maximum) {
        String result = optional(value, maximum);
        if (result == null) throw new IllegalStateException("MCP OAuth " + field + " is required");
        return result;
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > maximum || result.contains("\r") || result.contains("\n")) {
            throw new IllegalStateException("MCP OAuth configuration is invalid");
        }
        return result;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
