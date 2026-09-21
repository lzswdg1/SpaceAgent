package com.spaceagent.platform.tooling.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpRemoteEndpointPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OfficialGithubMcpOAuthMetadataResolver
        implements GithubMcpOAuthMetadataGateway {
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final Pattern RESOURCE_METADATA = Pattern.compile(
            "(?:^|[,\\s])resource_metadata\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private final McpRemoteEndpointPolicy endpointPolicy;
    private final RestClient http;
    private final ObjectMapper json;

    @Autowired
    public OfficialGithubMcpOAuthMetadataResolver(
            McpRemoteEndpointPolicy endpointPolicy,
            RestClient.Builder restClientBuilder,
            ObjectMapper json) {
        this(endpointPolicy, bounded(restClientBuilder), json);
    }

    public OfficialGithubMcpOAuthMetadataResolver(
            McpRemoteEndpointPolicy endpointPolicy,
            RestClient http,
            ObjectMapper json) {
        this.endpointPolicy = endpointPolicy;
        this.http = http;
        this.json = json;
    }

    @Override
    public OAuthServerMetadata resolve(McpConnection connection) {
        if (!GithubMcpProfiles.isOfficialRemote(connection)) {
            throw new IllegalArgumentException("Official GitHub MCP profile is required");
        }
        URI resourceEndpoint = endpointPolicy.validate(connection.endpointUrl());
        HttpResult challenge = get(resourceEndpoint);
        if (challenge.status() != 401) {
            throw new IllegalStateException("GitHub MCP did not return an OAuth challenge");
        }
        String challengeHeader = challenge.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        if (challengeHeader == null || challengeHeader.length() > 4096
                || challengeHeader.contains("\r") || challengeHeader.contains("\n")) {
            throw new IllegalStateException("GitHub MCP OAuth challenge is invalid");
        }
        Matcher matcher = RESOURCE_METADATA.matcher(challengeHeader);
        if (!matcher.find()) {
            throw new IllegalStateException("GitHub MCP resource metadata challenge is missing");
        }
        URI resourceMetadataUri = endpointPolicy.validate(matcher.group(1));
        requireHost(resourceMetadataUri, "api.githubcopilot.com", "resource metadata");
        JsonNode resourceMetadata = json(getOk(resourceMetadataUri));
        String resource = https(text(resourceMetadata, "resource", 2000), "resource");
        if (!sameResource(resourceEndpoint.toString(), resource)) {
            throw new IllegalStateException("GitHub MCP protected resource metadata mismatch");
        }
        JsonNode servers = resourceMetadata.path("authorization_servers");
        if (!servers.isArray() || servers.size() != 1) {
            throw new IllegalStateException("GitHub MCP authorization server metadata is invalid");
        }
        String authorizationServer = https(servers.get(0).asText(), "authorization server");
        URI authorizationServerUri = endpointPolicy.validate(authorizationServer);
        requireHost(authorizationServerUri, "github.com", "authorization server");
        if (!"/login/oauth".equals(trimSlash(authorizationServerUri.getPath()))) {
            throw new IllegalStateException("GitHub MCP authorization server path is invalid");
        }
        URI serverMetadataUri = endpointPolicy.validate(wellKnown(authorizationServerUri));
        JsonNode serverMetadata = json(getOk(serverMetadataUri));
        String authorizationEndpoint = https(
                text(serverMetadata, "authorization_endpoint", 2000),
                "authorization endpoint");
        String tokenEndpoint = https(
                text(serverMetadata, "token_endpoint", 2000), "token endpoint");
        requireGithubEndpoint(authorizationEndpoint, "/login/oauth/authorize");
        requireGithubEndpoint(tokenEndpoint, "/login/oauth/access_token");
        JsonNode challengeMethods = serverMetadata.path("code_challenge_methods_supported");
        if (!challengeMethods.isArray()
                || !contains(challengeMethods, "S256")) {
            throw new IllegalStateException("GitHub authorization server does not advertise PKCE S256");
        }
        return new OAuthServerMetadata(
                normalizeResource(resource), authorizationServer,
                authorizationEndpoint, tokenEndpoint,
                scopes(resourceMetadata.path("scopes_supported")));
    }

    private HttpResult get(URI uri) {
        try {
            return http.get().uri(uri).exchange((request, response) -> {
                byte[] body;
                try (InputStream input = response.getBody()) {
                    body = input.readNBytes(MAX_METADATA_BYTES + 1);
                }
                if (body.length > MAX_METADATA_BYTES) {
                    throw new IllegalStateException("OAuth metadata response exceeds limit");
                }
                return new HttpResult(
                        response.getStatusCode().value(),
                        HttpHeaders.readOnlyHttpHeaders(response.getHeaders()), body);
            });
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to retrieve GitHub MCP OAuth metadata");
        }
    }

    private byte[] getOk(URI uri) {
        HttpResult result = get(uri);
        if (result.status() != 200 || result.body().length == 0) {
            throw new IllegalStateException("GitHub MCP OAuth metadata request failed");
        }
        return result.body();
    }

    private JsonNode json(byte[] value) {
        try {
            return json.readTree(new String(value, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("GitHub MCP OAuth metadata is not valid JSON");
        }
    }

    private Set<String> scopes(JsonNode values) {
        if (!values.isArray() || values.size() > 100) {
            throw new IllegalStateException("GitHub MCP OAuth scopes metadata is invalid");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String scope = value.asText().trim();
            if (!scope.matches("[A-Za-z0-9:_-]{1,100}")) {
                throw new IllegalStateException("GitHub MCP OAuth scope is invalid");
            }
            result.add(scope);
        }
        return Set.copyOf(result);
    }

    private static String wellKnown(URI authorizationServer) {
        String path = trimSlash(authorizationServer.getRawPath());
        return authorizationServer.getScheme() + "://" + authorizationServer.getRawAuthority()
                + "/.well-known/oauth-authorization-server" + path;
    }

    private void requireGithubEndpoint(String value, String expectedPath) {
        URI uri = endpointPolicy.validate(value);
        requireHost(uri, "github.com", "OAuth endpoint");
        if (!expectedPath.equals(trimSlash(uri.getPath()))
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("GitHub OAuth endpoint is invalid");
        }
    }

    private static void requireHost(URI uri, String expected, String field) {
        if (!expected.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException("GitHub MCP " + field + " host is invalid");
        }
    }

    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equalsIgnoreCase(value.asText())) return true;
        }
        return false;
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        String normalized = value == null || value.isNull() ? "" : value.asText().trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalStateException("OAuth metadata missing " + field);
        }
        return normalized;
    }

    private static String https(String value, String field) {
        try {
            URI uri = URI.create(value).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (RuntimeException error) {
            throw new IllegalStateException(field + " must be HTTPS");
        }
    }

    private static boolean sameResource(String left, String right) {
        return normalizeResource(left).equalsIgnoreCase(normalizeResource(right));
    }

    private static String normalizeResource(String value) {
        return value.replaceAll("/+$", "");
    }

    private static String trimSlash(String value) {
        if (value == null || value.isBlank() || "/".equals(value)) return "";
        return value.replaceAll("/+$", "");
    }

    private static RestClient bounded(RestClient.Builder builder) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return builder.clone().requestFactory(requestFactory).build();
    }

    private record HttpResult(int status, HttpHeaders headers, byte[] body) {
    }
}
