package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;

import java.time.Instant;

public record RunEventView(
        String id,
        String agentRunId,
        long sequence,
        RunEventType type,
        String payload,
        ExecutionCursor cursor,
        Instant createdAt) {
}
