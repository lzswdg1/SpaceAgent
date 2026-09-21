package com.spaceagent.platform.tooling.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface McpConnectionProbeGateway {
    ProbeResult probe(McpConnection connection, Map<String, String> authorization);

    record ProbeResult(
            String protocolVersion,
            String serverName,
            String serverTitle,
            String serverVersion,
            String serverDescription,
            Map<String, Object> capabilities,
            List<ProbeTool> tools) {
        public ProbeResult {
            capabilities = immutable(capabilities);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record ProbeTool(
            String name,
            String title,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            boolean readOnly,
            boolean destructive,
            boolean idempotent,
            boolean openWorld) {
        public ProbeTool {
            inputSchema = immutable(inputSchema);
            outputSchema = immutable(outputSchema);
        }
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
