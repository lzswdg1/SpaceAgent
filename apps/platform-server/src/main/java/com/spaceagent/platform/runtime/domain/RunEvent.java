package com.spaceagent.platform.runtime.domain;

import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;

import java.time.Instant;
import java.util.Objects;

/** One append-only durable event in an AgentRun execution history. */
public record RunEvent(
        String id,
        String agentRunId,
        long sequence,
        RunEventType type,
        String payload,
        ExecutionCursor cursor,
        Instant createdAt) {

    public RunEvent {
        if (id == null || id.isBlank() || agentRunId == null || agentRunId.isBlank()) {
            throw new IllegalArgumentException("RunEvent identity is required");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        type = Objects.requireNonNull(type, "type");
        payload = payload == null || payload.isBlank() ? "{}" : payload;
        cursor = cursor == null ? ExecutionCursor.initial() : cursor;
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
