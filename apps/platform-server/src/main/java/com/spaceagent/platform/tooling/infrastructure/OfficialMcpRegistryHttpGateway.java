package com.spaceagent.platform.tooling.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spaceagent.platform.tooling.domain.McpRegistryCompatibility;
import com.spaceagent.platform.tooling.domain.McpRegistryGateway;
import com.spaceagent.platform.tooling.domain.McpRegistryGatewayException;
import com.spaceagent.platform.tooling.domain.McpRegistrySnapshot;
import com.spaceagent.platform.tooling.domain.McpRegistryStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class OfficialMcpRegistryHttpGateway implements McpRegistryGateway {
    private static final Pattern SERVER_NAME =
            Pattern.compile("^[a-zA-Z0-9.-]+/[a-zA-Z0-9._-]+$");
    private static final int MAXIMUM_MANIFEST_BYTES = 256_000;
    private static final int MAXIMUM_TRANSPORTS = 10;

    private final ObjectMapper json;
    private final McpToolingProperties.Registry properties;
    private final HttpClient client;

    @Autowired
    public OfficialMcpRegistryHttpGateway(ObjectMapper json, McpToolingProperties properties) {
        this(json, properties.getRegistry(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getRegistry().getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    OfficialMcpRegistryHttpGateway(
            ObjectMapper json, McpToolingProperties.Registry properties, HttpClient client) {
        this.json = json;
        this.properties = properties;
        this.client = client;
        validateProperties(properties);
    }

    @Override
    public RegistryPage fetchPage(String baseUrl, Instant updatedSince, String cursor, int limit) {
        URI base = officialBase(baseUrl);
        var builder = UriComponentsBuilder.fromUri(base).path("/v0.1/servers")
                .queryParam("limit", Math.max(1, Math.min(properties.getPageSize(), limit)));
        if (updatedSince != null) builder.queryParam("updated_since", updatedSince.toString());
        if (cursor != null && !cursor.isBlank()) builder.queryParam("cursor", cursor);
        URI uri = builder.build().encode().toUri();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", "SpaceAgent-MCP-Registry-Sync/1")
                .GET().build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {
                    // Do not retain external response details.
                }
                throw new McpRegistryGatewayException(
                        "MCP_REGISTRY_HTTP_STATUS", "Official MCP Registry returned a non-success status");
            }
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(properties.getMaximumResponseBytes() + 1);
            }
            if (body.length > properties.getMaximumResponseBytes()) {
                throw new McpRegistryGatewayException(
                        "MCP_REGISTRY_RESPONSE_TOO_LARGE", "Official MCP Registry response exceeded its bound");
            }
            return parsePage(body);
        } catch (McpRegistryGatewayException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpRegistryGatewayException(
                    "MCP_REGISTRY_INTERRUPTED", "Official MCP Registry request was interrupted", error);
        } catch (Exception error) {
            throw new McpRegistryGatewayException(
                    "MCP_REGISTRY_UNAVAILABLE", "Official MCP Registry is unavailable", error);
        }
    }

    RegistryPage parsePage(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode source = root.path("servers");
            if (!root.isObject() || !source.isArray() || source.size() > properties.getPageSize()) {
                throw invalid();
            }
            List<RegistryServer> servers = new ArrayList<>();
            for (JsonNode item : source) servers.add(server(item));
            JsonNode cursor = root.path("metadata").path("nextCursor");
            String next = cursor.isTextual() ? cursor.asText().trim() : null;
            if (next != null && (next.isEmpty() || next.length() > 2048)) next = null;
            return new RegistryPage(servers, next);
        } catch (McpRegistryGatewayException error) {
            throw error;
        } catch (Exception error) {
            throw new McpRegistryGatewayException(
                    "MCP_REGISTRY_RESPONSE_INVALID", "Official MCP Registry response is invalid", error);
        }
    }

    private RegistryServer server(JsonNode wrapper) {
        JsonNode server = wrapper.path("server");
        String name = text(server, "name", 200);
        String version = text(server, "version", 255);
        String description = text(server, "description", 100);
        if (!server.isObject() || !SERVER_NAME.matcher(name).matches()
                || version.equalsIgnoreCase("latest")) throw invalid();
        String title = optional(server, "title", 100);
        String schema = safeHttps(optional(server, "$schema", 1000));
        String repository = safeHttps(optional(server.path("repository"), "url", 1000));
        JsonNode official = wrapper.path("_meta").path("io.modelcontextprotocol.registry/official");
        McpRegistryStatus status = registryStatus(optional(official, "status", 24));
        String statusMessage = optional(official, "statusMessage", 500);
        Instant publishedAt = timestamp(optional(official, "publishedAt", 80));
        Instant updatedAt = timestamp(optional(official, "updatedAt", 80));

        List<McpRegistrySnapshot.RemoteTransport> transports = new ArrayList<>();
        JsonNode remotes = server.path("remotes");
        if (remotes.isArray()) {
            if (remotes.size() > MAXIMUM_TRANSPORTS) throw invalid();
            for (JsonNode remote : remotes) {
                if (!"streamable-http".equals(remote.path("type").asText())) continue;
                String endpoint = supportedEndpoint(remote.path("url").asText(null));
                if (endpoint == null) continue;
                JsonNode variables = sanitized(remote.path("variables"), false);
                JsonNode headers = headerMap(remote.path("headers"));
                transports.add(new McpRegistrySnapshot.RemoteTransport(
                        endpoint, canonical(variables.isMissingNode() ? json.createObjectNode() : variables),
                        canonical(headers),
                        hasSecretHeader(remote.path("headers"))));
            }
        }
        McpRegistryCompatibility compatibility;
        String compatibilityReason;
        if (version.length() > 80) {
            compatibility = McpRegistryCompatibility.INVALID_METADATA;
            compatibilityReason = "VERSION_EXCEEDS_MARKETPLACE_BOUND";
        } else if (transports.isEmpty()) {
            compatibility = McpRegistryCompatibility.UNSUPPORTED_TRANSPORT;
            compatibilityReason = "REMOTE_STREAMABLE_HTTPS_REQUIRED";
        } else {
            compatibility = McpRegistryCompatibility.SUPPORTED_REMOTE;
            compatibilityReason = null;
        }

        JsonNode clean = sanitized(wrapper, false);
        byte[] encoded = bytes(canonicalNode(clean));
        if (encoded.length > MAXIMUM_MANIFEST_BYTES) {
            throw new McpRegistryGatewayException(
                    "MCP_REGISTRY_MANIFEST_TOO_LARGE", "Registry manifest exceeded its bound");
        }
        return new RegistryServer(
                name, version, status, statusMessage, title, description, schema, repository,
                new String(encoded, java.nio.charset.StandardCharsets.UTF_8), publishedAt, updatedAt,
                transports, compatibility, compatibilityReason);
    }

    private JsonNode sanitized(JsonNode node, boolean redactValues) {
        if (node == null || node.isMissingNode() || node.isNull()) return node;
        if (node.isArray()) {
            ArrayNode copy = json.createArrayNode();
            node.forEach(value -> copy.add(sanitized(value, redactValues)));
            return copy;
        }
        if (!node.isObject()) return node.deepCopy();
        ObjectNode copy = json.createObjectNode();
        boolean secret = node.path("isSecret").asBoolean(false);
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if ((redactValues || secret) && (key.equals("value") || key.equals("default"))) continue;
            if (sensitiveKey(key) && field.getValue().isValueNode()) {
                copy.put(key, "[REDACTED]");
            } else {
                copy.set(key, sanitized(field.getValue(), redactValues || key.equals("headers")));
            }
        }
        return copy;
    }

    private ObjectNode headerMap(JsonNode headers) {
        ObjectNode result = json.createObjectNode();
        if (!headers.isArray()) return result;
        for (JsonNode header : headers) {
            String name = optional(header, "name", 200);
            if (name == null || name.isBlank()) throw invalid();
            JsonNode clean = sanitized(header, true);
            if (clean instanceof ObjectNode object) object.remove("name");
            result.set(name, clean);
        }
        return result;
    }

    private JsonNode canonicalNode(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode array = json.createArrayNode();
            node.forEach(value -> array.add(canonicalNode(value)));
            return array;
        }
        ObjectNode object = json.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(Comparator.naturalOrder());
        names.forEach(name -> object.set(name, canonicalNode(node.get(name))));
        return object;
    }

    private String canonical(JsonNode node) {
        return new String(bytes(canonicalNode(node)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] bytes(JsonNode node) {
        try {
            return json.writeValueAsBytes(node);
        } catch (Exception error) {
            throw invalid();
        }
    }

    private static String supportedEndpoint(String value) {
        if (value == null || value.isBlank() || value.length() > 2000
                || value.contains("{") || value.contains("}")) return null;
        try {
            URI uri = URI.create(value.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) return null;
            return uri.toString();
        } catch (Exception error) {
            return null;
        }
    }

    private static String safeHttps(String value) {
        return supportedEndpoint(value);
    }

    private static boolean hasSecretHeader(JsonNode headers) {
        if (!headers.isArray()) return false;
        for (JsonNode header : headers) if (header.path("isSecret").asBoolean(false)) return true;
        return false;
    }

    private static boolean sensitiveKey(String value) {
        String key = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return key.contains("password") || key.contains("token") || key.contains("apikey")
                || key.contains("privatekey") || key.contains("credential")
                || key.endsWith("secret");
    }

    private static McpRegistryStatus registryStatus(String value) {
        if (value == null || value.isBlank()) return McpRegistryStatus.ACTIVE;
        try {
            return McpRegistryStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception error) {
            throw invalid();
        }
    }

    private static Instant timestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception error) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String name, int maximum) {
        String value = optional(node, name, maximum);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private static String optional(JsonNode node, String name, int maximum) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        String result = value.asText().trim();
        if (result.length() > maximum) throw invalid();
        return result.isEmpty() ? null : result;
    }

    private static URI officialBase(String value) {
        try {
            URI uri = URI.create(value).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"registry.modelcontextprotocol.io".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || (uri.getPath() != null && !uri.getPath().isBlank() && !uri.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            return URI.create("https://registry.modelcontextprotocol.io");
        } catch (Exception error) {
            throw new McpRegistryGatewayException(
                    "MCP_REGISTRY_SOURCE_INVALID", "Official MCP Registry source is invalid", error);
        }
    }

    private static void validateProperties(McpToolingProperties.Registry value) {
        officialBase(value.getBaseUrl());
        if (value.getPageSize() < 1 || value.getPageSize() > 100
                || value.getPollDelayMs() < 100 || value.getPollDelayMs() > 60_000
                || value.getMaximumPages() < 1 || value.getMaximumPages() > 100
                || value.getMaximumServers() < value.getPageSize()
                || value.getMaximumServers() > 10_000
                || value.getMaximumResponseBytes() < 1024
                || value.getMaximumResponseBytes() > 5_000_000
                || value.getConnectTimeoutSeconds() < 1 || value.getConnectTimeoutSeconds() > 30
                || value.getRequestTimeoutSeconds() < 1 || value.getRequestTimeoutSeconds() > 60
                || value.getLeaseSeconds() < 30 || value.getLeaseSeconds() > 600
                || value.getMaximumAttempts() < 1 || value.getMaximumAttempts() > 10
                || (value.getWorkerId() != null && value.getWorkerId().trim().length() > 160)) {
            throw new IllegalStateException("MCP Registry synchronization configuration is invalid");
        }
    }

    private static McpRegistryGatewayException invalid() {
        return new McpRegistryGatewayException(
                "MCP_REGISTRY_RESPONSE_INVALID", "Official MCP Registry response is invalid");
    }
}
