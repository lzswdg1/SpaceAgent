package com.spaceagent.platform.tooling.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.McpRemoteEndpointPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GenericMcpOAuthMetadataResolver implements McpOAuthMetadataGateway {
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final Pattern RESOURCE_METADATA = Pattern.compile(
            "(?:^|[,\\s])resource_metadata\\s*=\\s*(?:\"([^\"]+)\"|([^,\\s]+))",
            Pattern.CASE_INSENSITIVE);

    private final McpRemoteEndpointPolicy endpointPolicy;
    private final RestClient http;
    private final ObjectMapper json;

    @Autowired
    public GenericMcpOAuthMetadataResolver(
            McpRemoteEndpointPolicy endpointPolicy,
            RestClient.Builder restClientBuilder,
            ObjectMapper json) {
        this(endpointPolicy, bounded(restClientBuilder), json);
    }

    public GenericMcpOAuthMetadataResolver(
            McpRemoteEndpointPolicy endpointPolicy, RestClient http, ObjectMapper json) {
        this.endpointPolicy = endpointPolicy;
        this.http = http;
        this.json = json;
    }

    @Override
    public OAuthServerMetadata resolve(
            McpConnection connection, Set<String> allowedAuthorizationServers) {
        if (allowedAuthorizationServers == null || allowedAuthorizationServers.isEmpty()) {
            throw new IllegalStateException("No MCP OAuth client registration is configured");
        }
        URI resourceEndpoint = endpointPolicy.validate(connection.endpointUrl());
        HttpResult challenge = get(resourceEndpoint);
        URI resourceMetadataUri = resourceMetadataUri(resourceEndpoint, challenge);
        JsonNode resourceMetadata = json(getOk(resourceMetadataUri));
        String resource = https(text(resourceMetadata, "resource", 2_000), "resource");
        if (!sameResource(resourceEndpoint.toString(), resource)) {
            throw new IllegalStateException("MCP protected resource metadata mismatch");
        }
        String authorizationServer = authorizationServer(
                resourceMetadata.path("authorization_servers"), allowedAuthorizationServers);
        JsonNode serverMetadata = authorizationServerMetadata(authorizationServer);
        String issuer = ConfiguredMcpOAuthClientRegistrationProvider.authorizationServer(
                text(serverMetadata, "issuer", 2_000));
        if (!issuer.equals(authorizationServer)) {
            throw new IllegalStateException("MCP OAuth authorization server issuer mismatch");
        }
        JsonNode challengeMethods = serverMetadata.path("code_challenge_methods_supported");
        if (!challengeMethods.isArray() || !contains(challengeMethods, "S256")) {
            throw new IllegalStateException(
                    "MCP OAuth authorization server does not advertise PKCE S256");
        }
        String authorizationEndpoint = endpoint(
                text(serverMetadata, "authorization_endpoint", 2_000),
                "authorization endpoint");
        String tokenEndpoint = endpoint(
                text(serverMetadata, "token_endpoint", 2_000), "token endpoint");
        Set<String> scopes = tokens(resourceMetadata.path("scopes_supported"), 100);
        if (scopes.isEmpty()) scopes = tokens(serverMetadata.path("scopes_supported"), 100);
        Set<String> authenticationMethods = tokens(
                serverMetadata.path("token_endpoint_auth_methods_supported"), 20);
        return new OAuthServerMetadata(
                normalizeResource(resource), authorizationServer, authorizationEndpoint,
                tokenEndpoint, scopes, authenticationMethods);
    }

    private URI resourceMetadataUri(URI resourceEndpoint, HttpResult challenge) {
        if (challenge.status() == 401) {
            String header = challenge.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE);
            if (header != null) {
                if (header.length() > 4_096 || header.contains("\r") || header.contains("\n")) {
                    throw new IllegalStateException("MCP OAuth challenge is invalid");
                }
                Matcher matcher = RESOURCE_METADATA.matcher(header);
                if (matcher.find()) {
                    String value = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
                    return endpointPolicy.validate(value);
                }
            }
        }
        return endpointPolicy.validate(wellKnownResource(resourceEndpoint));
    }

    private String authorizationServer(
            JsonNode values, Set<String> allowedAuthorizationServers) {
        if (!values.isArray() || values.isEmpty() || values.size() > 10) {
            throw new IllegalStateException("MCP authorization server metadata is invalid");
        }
        for (JsonNode value : values) {
            String candidate = ConfiguredMcpOAuthClientRegistrationProvider.authorizationServer(
                    value.asText());
            if (allowedAuthorizationServers.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("MCP authorization server has no configured client");
    }

    private JsonNode authorizationServerMetadata(String authorizationServer) {
        URI issuer = URI.create(authorizationServer);
        for (String candidate : List.of(
                oauthMetadataUri(issuer), oidcMetadataUri(issuer))) {
            URI uri = endpointPolicy.validate(candidate);
            HttpResult result = get(uri);
            if (result.status() == 200 && result.body().length > 0) {
                return json(result.body());
            }
        }
        throw new IllegalStateException("MCP OAuth authorization server metadata request failed");
    }

    private HttpResult get(URI uri) {
        try {
            return http.get().uri(uri).exchange((request, response) -> {
                byte[] body;
                try (InputStream input = response.getBody()) {
                    body = input.readNBytes(MAX_METADATA_BYTES + 1);
                }
                if (body.length > MAX_METADATA_BYTES) {
                    throw new IllegalStateException("MCP OAuth metadata response exceeds limit");
                }
                return new HttpResult(
                        response.getStatusCode().value(),
                        HttpHeaders.readOnlyHttpHeaders(response.getHeaders()), body);
            });
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to retrieve MCP OAuth metadata");
        }
    }

    private byte[] getOk(URI uri) {
        HttpResult result = get(uri);
        if (result.status() != 200 || result.body().length == 0) {
            throw new IllegalStateException("MCP OAuth metadata request failed");
        }
        return result.body();
    }

    private JsonNode json(byte[] value) {
        try {
            JsonNode result = json.readTree(new String(value, StandardCharsets.UTF_8));
            if (result == null || !result.isObject()) throw new IllegalStateException();
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("MCP OAuth metadata is not valid JSON");
        }
    }

    private String endpoint(String value, String field) {
        URI uri = endpointPolicy.validate(value);
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException("MCP OAuth " + field + " is invalid");
        }
        return uri.toString();
    }

    private static Set<String> tokens(JsonNode values, int maximum) {
        if (values == null || values.isMissingNode() || values.isNull()) return Set.of();
        if (!values.isArray() || values.size() > maximum) {
            throw new IllegalStateException("MCP OAuth token metadata is invalid");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String token = value.asText().trim();
            if (!token.matches("[A-Za-z0-9:._/-]{1,100}")) {
                throw new IllegalStateException("MCP OAuth metadata token is invalid");
            }
            result.add(token);
        }
        return Set.copyOf(result);
    }

    private static String wellKnownResource(URI resource) {
        String path = resource.getRawPath();
        if (path == null || path.isBlank() || "/".equals(path)) path = "";
        return resource.getScheme() + "://" + resource.getRawAuthority()
                + "/.well-known/oauth-protected-resource" + path;
    }

    private static String oauthMetadataUri(URI issuer) {
        String path = issuer.getRawPath();
        if (path == null || path.isBlank() || "/".equals(path)) path = "";
        return issuer.getScheme() + "://" + issuer.getRawAuthority()
                + "/.well-known/oauth-authorization-server" + path;
    }

    private static String oidcMetadataUri(URI issuer) {
        return trimTrailingSlash(issuer.toString()) + "/.well-known/openid-configuration";
    }

    private static String text(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        String result = value == null || value.isNull() ? "" : value.asText().trim();
        if (result.isEmpty() || result.length() > maximum
                || result.contains("\r") || result.contains("\n")) {
            throw new IllegalStateException("MCP OAuth metadata missing " + field);
        }
        return result;
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
            throw new IllegalStateException("MCP OAuth " + field + " must be HTTPS");
        }
    }

    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) return true;
        }
        return false;
    }

    private static boolean sameResource(String left, String right) {
        return normalizeResource(left).equalsIgnoreCase(normalizeResource(right));
    }

    private static String normalizeResource(String value) {
        return trimTrailingSlash(value);
    }

    private static String trimTrailingSlash(String value) {
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
