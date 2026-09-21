package com.spaceagent.platform.observability.domain;

import java.time.Instant;

public record TraceFilter(
        String tenantId,
        String ownerId,
        TraceStatus status,
        String agentId,
        String sessionId,
        String keyword,
        Long minDurationMs,
        Long maxDurationMs,
        Instant since,
        Instant until) {}
