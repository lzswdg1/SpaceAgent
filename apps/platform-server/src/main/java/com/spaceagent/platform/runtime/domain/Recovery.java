package com.spaceagent.platform.runtime.domain;

import java.time.Instant;

/**
 * A recovery attempt for an interrupted or failed {@link AgentRun}.
 */
public record Recovery(
        String id,
        String agentRunId,
        int attempt,
        RecoveryState state,
        String reason,
        Instant createdAt,
        Instant completedAt) {
}
