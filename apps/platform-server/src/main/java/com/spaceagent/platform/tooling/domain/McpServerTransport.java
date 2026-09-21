package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpServerTransport(
        String id,
        String serverVersionId,
        int position,
        McpTransportType transportType,
        String endpointTemplate,
        boolean endpointConfigurable,
        String variablesSchemaJson,
        String headersSchemaJson,
        boolean enabled,
        Instant createdAt) {
}
