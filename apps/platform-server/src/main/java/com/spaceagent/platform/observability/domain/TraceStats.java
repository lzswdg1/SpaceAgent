package com.spaceagent.platform.observability.domain;

public record TraceStats(
        long totalTraces,
        long successTraces,
        long errorTraces,
        long abortedTraces,
        double averageDurationMs,
        double p50DurationMs,
        double p95DurationMs,
        double averageFirstTokenMs,
        long totalTokens,
        Double totalCostUsd) {}
