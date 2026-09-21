package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi.CapabilityView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RuntimeToolDefinitions {
    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB]"
                    + "[0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";

    private RuntimeToolDefinitions() {
    }

    static List<CapabilityView> all(
            boolean webSearchAvailable,
            boolean sandboxCommandAvailable) {
        List<CapabilityView> tools = new ArrayList<>();
        tools.add(tool("echo", "Echo", "Return a short text value through the isolated Tool ledger.",
                "SANDBOX", schema(props("text", string(4_000)), "text"),
                true, true, false, false));
        tools.add(tool("web_search", "Web Search",
                "Search the configured SearXNG JSON API and return bounded source snippets.",
                "SEARXNG", schema(props(
                        "query", string(500),
                        "maxResults", integer(1, 10),
                        "language", patternString(32, "^[A-Za-z0-9_-]{1,32}$"),
                        "categories", patternString(100, "^[A-Za-z0-9_,-]{1,100}$"),
                        "timeRange", enumeration("day", "month", "year"),
                        "approvalId", string(64)), "query"),
                webSearchAvailable, true, true, false));
        tools.add(tool("http_fetch", "HTTP Fetch",
                "Fetch one public HTTPS text/HTML/JSON resource without redirects.",
                "BOUNDED_HTTP", schema(props(
                        "url", string(2_000),
                        "maxCharacters", integer(1_000, 200_000),
                        "approvalId", string(64)), "url"),
                true, true, true, false));
        tools.add(tool("knowledge_search", "Knowledge Search",
                "Search the active Agent's bound Knowledge documents.",
                "KNOWLEDGE", schema(props(
                        "query", string(4_000),
                        "topK", integer(1, 20)), "query"),
                true, true, false, false));
        tools.add(tool("file_read", "File Read",
                "Read a bounded UTF-8 file inside the Run's READY managed Workspace.",
                "WORKSPACE_READ", schema(props(
                        "workspaceId", uuid(), "path", path(),
                        "maxCharacters", integer(1_000, 200_000)), "workspaceId", "path"),
                sandboxCommandAvailable, true, false, true));
        tools.add(tool("file_list", "File List",
                "List bounded files below a relative Workspace directory.",
                "WORKSPACE_READ", schema(props(
                        "workspaceId", uuid(), "path", path(),
                        "maxDepth", integer(0, 8), "maxEntries", integer(1, 500)),
                        "workspaceId"),
                sandboxCommandAvailable, true, false, true));
        tools.add(tool("git_status", "Git Status",
                "Read porcelain Git status and changed-file evidence for the Workspace.",
                "WORKSPACE_GIT", schema(props("workspaceId", uuid()), "workspaceId"),
                sandboxCommandAvailable, true, false, true));
        tools.add(tool("git_diff", "Git Diff",
                "Read a bounded binary-safe Git patch for the Workspace.",
                "WORKSPACE_GIT", schema(props(
                        "workspaceId", uuid(),
                        "maxCharacters", integer(1_000, 200_000)), "workspaceId"),
                sandboxCommandAvailable, true, false, true));
        tools.add(tool("mcp_call", "MCP Dynamic Tool",
                "Call one advertised Tool on an authorized MCP Connection.",
                "MCP", schema(props(
                        "connectionId", uuid(), "remoteTool", identifier(200),
                        "arguments", object(50), "approvalId", string(64)),
                        "connectionId", "remoteTool", "arguments"),
                true, false, true, false));
        tools.add(tool("github_search_repositories", "GitHub Repository Search",
                "Search repositories through an authorized GitHub MCP Connection.",
                "GITHUB_MCP", schema(props(
                        "connectionId", uuid(), "query", string(256),
                        "maxResults", integer(1, 20), "approvalId", string(64)),
                        "connectionId", "query"),
                true, true, true, false));
        tools.add(tool("github_get_repository", "GitHub Repository",
                "Resolve exact GitHub repository metadata through MCP.",
                "GITHUB_MCP", schema(props(
                        "connectionId", uuid(), "githubUrl", string(2_000),
                        "approvalId", string(64)), "connectionId", "githubUrl"),
                true, true, true, false));
        tools.add(tool("document_read", "Document Read",
                "Extract bounded text and metadata from a Workspace document.",
                "DOCUMENT", schema(props(
                        "workspaceId", uuid(), "path", path(),
                        "maxCharacters", integer(1_000, 200_000)), "workspaceId", "path"),
                sandboxCommandAvailable, true, false, true));
        tools.add(tool("document_write", "Document Write",
                "Write text, Markdown, HTML or DOCX inside the managed Workspace.",
                "DOCUMENT", schema(props(
                        "workspaceId", uuid(), "path", path(), "content", string(200_000),
                        "format", enumeration("text", "markdown", "html", "docx"),
                        "approvalId", string(64)),
                        "workspaceId", "path", "content", "format"),
                sandboxCommandAvailable, false, false, true));
        tools.add(tool("document_workspace_read", "Document Workspace Read",
                "Read bounded UTF-8 content from a non-Project document Workspace.",
                "DOCUMENT_WORKSPACE", schema(props("workspaceId", uuid(), "path", path(),
                        "maxCharacters", integer(1_000, 200_000)), "workspaceId", "path"),
                sandboxCommandAvailable, true, false, false));
        tools.add(tool("document_workspace_list", "Document Workspace List",
                "List bounded entries in a non-Project document Workspace.",
                "DOCUMENT_WORKSPACE", schema(props("workspaceId", uuid(), "path", path(),
                        "maxDepth", integer(0, 8), "maxEntries", integer(1, 500)), "workspaceId"),
                sandboxCommandAvailable, true, false, false));
        tools.add(tool("document_workspace_write", "Document Workspace Write",
                "Write bounded UTF-8 content to a governed non-Project document Workspace.",
                "DOCUMENT_WORKSPACE", schema(props("workspaceId", uuid(), "path", path(),
                        "content", string(500_000), "approvalId", string(64)),
                        "workspaceId", "path", "content"), sandboxCommandAvailable, false, false, false));
        tools.add(tool("document_workspace_delete", "Document Workspace Delete",
                "Delete one file from a governed non-Project document Workspace.",
                "DOCUMENT_WORKSPACE", schema(props("workspaceId", uuid(), "path", path(),
                        "approvalId", string(64)), "workspaceId", "path"),
                sandboxCommandAvailable, false, false, false));
        tools.add(tool("coding_write_file", "Coding Write File",
                "Write one relative Workspace file through Coding Runtime.",
                "CODING", schema(props(
                        "workspaceId", uuid(), "path", path(), "content", string(500_000),
                        "approvalId", string(64)), "workspaceId", "path", "content"),
                sandboxCommandAvailable, false, false, true));
        tools.add(tool("coding_delete_file", "Coding Delete File",
                "Delete one relative Workspace file through Coding Runtime.",
                "CODING", schema(props(
                        "workspaceId", uuid(), "path", path(),
                        "approvalId", string(64)), "workspaceId", "path"),
                sandboxCommandAvailable, false, false, true));
        tools.add(tool("coding_run_command", "Coding Run Command",
                "Run one allowlisted build/test/Git command through Coding Runtime.",
                "CODING", schema(props(
                        "workspaceId", uuid(),
                        "executable", enumeration("./mvnw", "mvn", "npm", "pnpm", "go", "git"),
                        "arguments", array(string(1_000), 50),
                        "timeoutSeconds", integer(1, 600),
                        "approvalId", string(64)), "workspaceId", "executable"),
                sandboxCommandAvailable, false, false, true));
        return List.copyOf(tools);
    }

    private static CapabilityView tool(
            String id, String name, String description, String mode,
            Map<String, Object> schema, boolean available, boolean readOnly,
            boolean network, boolean workspace) {
        return new CapabilityView(
                id, name, description, mode, schema, available, readOnly, network, workspace);
    }

    private static Map<String, Object> schema(
            Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(required));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> props(Object... values) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            properties.put(String.valueOf(values[index]), values[index + 1]);
        }
        return Map.copyOf(properties);
    }

    private static Map<String, Object> string(int maxLength) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maxLength);
    }

    private static Map<String, Object> patternString(int maxLength, String pattern) {
        return Map.of(
                "type", "string", "minLength", 1,
                "maxLength", maxLength, "pattern", pattern);
    }

    private static Map<String, Object> identifier(int maxLength) {
        return patternString(maxLength, "^[A-Za-z0-9_.:/-]+$");
    }

    private static Map<String, Object> path() {
        return Map.of(
                "type", "string", "minLength", 1, "maxLength", 1_000,
                "pattern", "^(?!/)(?!.*(?:^|/)\\.\\.(?:/|$)).+$");
    }

    private static Map<String, Object> uuid() {
        return patternString(36, UUID_PATTERN);
    }

    private static Map<String, Object> integer(int minimum, int maximum) {
        return Map.of(
                "type", "integer", "minimum", minimum, "maximum", maximum);
    }

    private static Map<String, Object> enumeration(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> object(int maxProperties) {
        return Map.of("type", "object", "maxProperties", maxProperties);
    }

    private static Map<String, Object> array(Map<String, Object> items, int maxItems) {
        return Map.of(
                "type", "array", "items", items, "maxItems", maxItems);
    }
}
