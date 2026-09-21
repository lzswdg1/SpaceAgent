package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.HandoffSnapshot;
import com.spaceagent.platform.runtime.domain.HandoffState;

import java.time.Instant;

/**
 * Public typed handoff query result.
 */
public record HandoffView(
        String id,
        String sourceAgentRunId,
        String targetAgentRunId,
        HandoffState state,
        HandoffSnapshot snapshot,
        Instant createdAt,
        Instant completedAt) {
}
