package com.spaceagent.platform.runtime.api;

import java.time.Instant;

/**
 * Public runtime checkpoint query result.
 */
public record CheckpointView(
        String id,
        String agentRunId,
        int sequence,
        String stateSnapshot,
        Instant createdAt) {
}
