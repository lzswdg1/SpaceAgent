package com.spaceagent.platform.observability.domain;

import java.time.Instant;

public record ObservabilityTimeseriesPoint(
        Instant bucket,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheCreateTokens,
        Long costMicros,
        long calls,
        long activeSessions) {
}
