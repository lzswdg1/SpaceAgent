package com.spaceagent.platform.tooling.api;

import java.util.Map;

/** Public Tooling boundary used only by Java Runtime's ledgered dispatcher. */
public interface ExternalRuntimeToolApplicationApi {
    ToolResult execute(ExecuteCommand command);

    McpToolDescriptor describeMcpTool(
            String tenantId, String userId, String connectionId, String remoteTool);

    record ExecuteCommand(
            String tenantId,
            String userId,
            String toolId,
            Map<String, Object> arguments,
            Boolean expectedMcpReadOnly) {
        public ExecuteCommand {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record ToolResult(String content, boolean remoteReadOnly, boolean remoteError) {
    }

    record McpToolDescriptor(
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            boolean destructive) {
        public McpToolDescriptor {
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }
}
