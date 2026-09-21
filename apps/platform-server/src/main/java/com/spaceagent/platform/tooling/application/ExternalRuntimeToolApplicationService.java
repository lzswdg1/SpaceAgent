package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.spaceagent.platform.tooling.api.ExternalRuntimeToolApplicationApi;
import com.spaceagent.platform.tooling.api.GithubMcpApplicationApi;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.domain.HttpFetchGateway;
import com.spaceagent.platform.tooling.domain.WebSearchGateway;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExternalRuntimeToolApplicationService
        implements ExternalRuntimeToolApplicationApi {
    private static final int MAX_RESULT_CHARACTERS = 200_000;
    private final WebSearchGateway webSearch;
    private final HttpFetchGateway httpFetch;
    private final McpRemoteToolApplicationApi mcp;
    private final GithubMcpApplicationApi github;
    private final ObjectMapper json;

    public ExternalRuntimeToolApplicationService(
            WebSearchGateway webSearch,
            HttpFetchGateway httpFetch,
            McpRemoteToolApplicationApi mcp,
            GithubMcpApplicationApi github,
            ObjectMapper json) {
        this.webSearch = webSearch;
        this.httpFetch = httpFetch;
        this.mcp = mcp;
        this.github = github;
        this.json = json;
    }

    @Override
    public ToolResult execute(ExecuteCommand command) {
        return switch (command.toolId()) {
            case "web_search" -> new ToolResult(write(webSearch.search(
                    new WebSearchGateway.SearchRequest(
                            string(command.arguments(), "query"),
                            integer(command.arguments(), "maxResults", 5),
                            optional(command.arguments(), "language"),
                            optional(command.arguments(), "categories"),
                            optional(command.arguments(), "timeRange")))), true, false);
            case "http_fetch" -> new ToolResult(write(httpFetch.fetch(
                    string(command.arguments(), "url"),
                    integer(command.arguments(), "maxCharacters", 50_000))), true, false);
            case "mcp_call" -> executeMcp(command);
            case "github_search_repositories" -> new ToolResult(write(
                    github.searchRepositories(new GithubMcpApplicationApi.SearchCommand(
                            command.tenantId(), command.userId(),
                            string(command.arguments(), "connectionId"),
                            string(command.arguments(), "query"),
                            integer(command.arguments(), "maxResults", 10)))), true, false);
            case "github_get_repository" -> new ToolResult(write(github.discover(
                    new GithubMcpApplicationApi.DiscoverCommand(
                            command.tenantId(), command.userId(),
                            string(command.arguments(), "connectionId"),
                            string(command.arguments(), "githubUrl")))), true, false);
            default -> throw new IllegalArgumentException("Unsupported external Runtime Tool");
        };
    }

    @Override
    public McpToolDescriptor describeMcpTool(
            String tenantId, String userId, String connectionId, String remoteTool) {
        String name = safeRemoteName(remoteTool);
        McpRemoteToolApplicationApi.ToolView tool = mcp.listTools(
                        tenantId, userId, connectionId).stream()
                .filter(value -> value.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP Tool is not advertised"));
        boolean effectiveReadOnly = tool.readOnly() && !tool.destructive();
        return new McpToolDescriptor(
                tool.name(), tool.description(), tool.inputSchema(),
                effectiveReadOnly, tool.destructive());
    }

    private ToolResult executeMcp(ExecuteCommand command) {
        String connectionId = string(command.arguments(), "connectionId");
        String remoteTool = safeRemoteName(string(command.arguments(), "remoteTool"));
        McpToolDescriptor descriptor = describeMcpTool(
                command.tenantId(), command.userId(), connectionId, remoteTool);
        if (command.expectedMcpReadOnly() == null
                || command.expectedMcpReadOnly() != descriptor.readOnly()) {
            throw new IllegalStateException("MCP Tool annotations changed before execution");
        }
        Map<String, Object> arguments = object(command.arguments().get("arguments"));
        JsonNode node = json.valueToTree(arguments);
        JsonNode schema = boundedRemoteSchema(descriptor.inputSchema());
        if (!SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schema)
                .validate(node).isEmpty()) {
            throw new IllegalArgumentException("MCP Tool arguments failed advertised schema");
        }
        McpRemoteToolApplicationApi.ToolResult result = mcp.callTool(
                new McpRemoteToolApplicationApi.CallCommand(
                        command.tenantId(), command.userId(), connectionId,
                        remoteTool, arguments));
        String content = result.structuredContent() == null
                ? result.text() : write(result.structuredContent());
        return new ToolResult(bound(content), descriptor.readOnly(), result.error());
    }

    private JsonNode boundedRemoteSchema(Map<String, Object> value) {
        JsonNode schema = json.valueToTree(value);
        if (schema.toString().length() > 100_000) {
            throw new IllegalArgumentException("MCP Tool schema exceeds limit");
        }
        inspectSchema(schema, 0, new int[]{0});
        return schema;
    }

    private static void inspectSchema(JsonNode node, int depth, int[] nodes) {
        if (depth > 20 || ++nodes[0] > 5_000) {
            throw new IllegalArgumentException("MCP Tool schema is too complex");
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if ("$ref".equals(field.getKey())
                        || "$dynamicRef".equals(field.getKey())) {
                    throw new IllegalArgumentException(
                            "MCP Tool schema references are not allowed");
                }
                inspectSchema(field.getValue(), depth + 1, nodes);
            }
        } else if (node.isArray()) {
            node.forEach(child -> inspectSchema(child, depth + 1, nodes));
        }
    }

    private String write(Object value) {
        try {
            return bound(json.writeValueAsString(value));
        } catch (Exception error) {
            throw new IllegalStateException("Runtime Tool result serialization failed");
        }
    }

    private static String string(Map<String, Object> values, String key) {
        String value = optional(values, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String optional(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> input)) {
            throw new IllegalArgumentException("arguments must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return Map.copyOf(result);
    }

    private static String safeRemoteName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 200
                || !normalized.matches("[A-Za-z0-9_.:/-]+")) {
            throw new IllegalArgumentException("remoteTool is invalid");
        }
        return normalized;
    }

    private static String bound(String value) {
        String normalized = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), MAX_RESULT_CHARACTERS));
    }
}
