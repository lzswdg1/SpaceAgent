package com.spaceagent.platform.runtime.domain;

import java.time.Instant;

/**
 * A durable handoff between two agent runs.
 */
public record Handoff(
        String id,
        String sourceAgentRunId,
        String targetAgentRunId,
        HandoffState state,
        HandoffSnapshot snapshot,
        Instant createdAt,
        Instant completedAt) {
}
