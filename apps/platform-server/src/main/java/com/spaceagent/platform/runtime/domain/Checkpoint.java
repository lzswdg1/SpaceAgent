package com.spaceagent.platform.runtime.domain;

import java.time.Instant;

/**
 * A serialized runtime snapshot that enables deterministic resume or retry.
 */
public record Checkpoint(
        String id,
        String agentRunId,
        int sequence,
        String stateSnapshot,
        Instant createdAt) {
}
