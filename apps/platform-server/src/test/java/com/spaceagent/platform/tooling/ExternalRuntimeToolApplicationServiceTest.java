package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.ExternalRuntimeToolApplicationApi;
import com.spaceagent.platform.tooling.api.GithubMcpApplicationApi;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.application.ExternalRuntimeToolApplicationService;
import com.spaceagent.platform.tooling.domain.HttpFetchGateway;
import com.spaceagent.platform.tooling.domain.WebSearchGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalRuntimeToolApplicationServiceTest {

    @Test
    void invokesOnlyAdvertisedMcpToolAfterSchemaAndAnnotationCheck() {
        McpRemoteToolApplicationApi mcp = mock(McpRemoteToolApplicationApi.class);
        var service = service(mcp);
        Map<String, Object> remoteSchema = Map.of(
                "$schema", "https://json-schema.org/draft/2020-12/schema",
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path"),
                "additionalProperties", false);
        when(mcp.listTools("tenant", "user", connectionId())).thenReturn(List.of(
                new McpRemoteToolApplicationApi.ToolView(
                        "repo.create_issue", "Create issue", remoteSchema,
                        false, true)));
        when(mcp.callTool(any())).thenReturn(
                new McpRemoteToolApplicationApi.ToolResult(
                        false, Map.of("number", 42), "created"));

        Map<String, Object> arguments = Map.of(
                "connectionId", connectionId(),
                "remoteTool", "repo.create_issue",
                "arguments", Map.of("path", "owner/repo"));
        var result = service.execute(new ExternalRuntimeToolApplicationApi.ExecuteCommand(
                "tenant", "user", "mcp_call", arguments, false));

        assertThat(result.remoteReadOnly()).isFalse();
        assertThat(result.remoteError()).isFalse();
        assertThat(result.content()).contains("\"number\":42");
        verify(mcp).callTool(any());
    }

    @Test
    void blocksMcpSchemaMismatchAndChangedAnnotations() {
        McpRemoteToolApplicationApi mcp = mock(McpRemoteToolApplicationApi.class);
        var service = service(mcp);
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("count", Map.of("type", "integer")),
                "required", List.of("count"));
        when(mcp.listTools("tenant", "user", connectionId())).thenReturn(List.of(
                new McpRemoteToolApplicationApi.ToolView(
                        "repo.lookup", "Lookup", schema, true, false)));

        Map<String, Object> invalid = Map.of(
                "connectionId", connectionId(), "remoteTool", "repo.lookup",
                "arguments", Map.of("count", "not-an-integer"));
        assertThatThrownBy(() -> service.execute(
                new ExternalRuntimeToolApplicationApi.ExecuteCommand(
                        "tenant", "user", "mcp_call", invalid, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");

        Map<String, Object> valid = Map.of(
                "connectionId", connectionId(), "remoteTool", "repo.lookup",
                "arguments", Map.of("count", 1));
        assertThatThrownBy(() -> service.execute(
                new ExternalRuntimeToolApplicationApi.ExecuteCommand(
                        "tenant", "user", "mcp_call", valid, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("annotations changed");

        when(mcp.listTools("tenant", "user", connectionId())).thenReturn(List.of(
                new McpRemoteToolApplicationApi.ToolView(
                        "repo.lookup", "Lookup", Map.of("$ref", "https://evil/schema"),
                        true, false)));
        assertThatThrownBy(() -> service.execute(
                new ExternalRuntimeToolApplicationApi.ExecuteCommand(
                        "tenant", "user", "mcp_call", valid, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("references");

        when(mcp.listTools("tenant", "user", connectionId())).thenReturn(List.of(
                new McpRemoteToolApplicationApi.ToolView(
                        "repo.lookup", "Contradictory hints", schema, true, true)));
        assertThat(service.describeMcpTool(
                "tenant", "user", connectionId(), "repo.lookup").readOnly()).isFalse();
        verify(mcp, never()).callTool(any());
    }

    private static ExternalRuntimeToolApplicationService service(
            McpRemoteToolApplicationApi mcp) {
        WebSearchGateway search = mock(WebSearchGateway.class);
        HttpFetchGateway fetch = mock(HttpFetchGateway.class);
        GithubMcpApplicationApi github = mock(GithubMcpApplicationApi.class);
        return new ExternalRuntimeToolApplicationService(
                search, fetch, mcp, github, new ObjectMapper());
    }

    private static String connectionId() {
        return "00000000-0000-4000-8000-000000000010";
    }
}
