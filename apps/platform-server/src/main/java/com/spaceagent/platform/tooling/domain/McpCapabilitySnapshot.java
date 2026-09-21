package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpCapabilitySnapshot(
        String id,
        String connectionId,
        long connectionRevision,
        String protocolVersion,
        String serverName,
        String serverTitle,
        String serverVersion,
        String serverDescription,
        String capabilitiesJson,
        String toolsJson,
        String snapshotSha256,
        Instant observedAt) {
}
