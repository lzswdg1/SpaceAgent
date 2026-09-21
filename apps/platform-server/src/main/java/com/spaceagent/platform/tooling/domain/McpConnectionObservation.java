package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpConnectionObservation(
        String id,
        String connectionId,
        long connectionRevision,
        McpConnectionObservationOutcome outcome,
        long latencyMs,
        String protocolVersion,
        String capabilitySnapshotId,
        String safeErrorCode,
        Instant observedAt) {
}
