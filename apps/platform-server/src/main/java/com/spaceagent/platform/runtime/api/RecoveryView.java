package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.RecoveryState;

import java.time.Instant;

/**
 * Public recovery-attempt query result.
 */
public record RecoveryView(
        String id,
        String agentRunId,
        int attempt,
        RecoveryState state,
        String reason,
        Instant createdAt,
        Instant completedAt) {
}
