package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.RunStepState;

import java.time.Instant;

/**
 * Public run-step query result.
 */
public record RunStepView(
        String id,
        String agentRunId,
        int sequence,
        String type,
        RunStepState state,
        Instant createdAt,
        Instant completedAt) {
}
