package com.spaceagent.platform.runtime.domain;

import java.time.Instant;

/**
 * A single durable step inside an {@link AgentRun}.
 */
public record RunStep(
        String id,
        String agentRunId,
        int sequence,
        String type,
        RunStepState state,
        Instant createdAt,
        Instant completedAt) {
}
