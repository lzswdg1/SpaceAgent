package com.spaceagent.platform.tooling.domain;

/**
 * Execution metadata returned by the sandbox worker.
 */
public record SandboxExecutionMetadata(
        long startedAtEpochMs,
        long completedAtEpochMs,
        long wallTimeMs,
        boolean timedOut,
        long outputBytes,
        SandboxResourceMetrics resourceMetrics) {
    public SandboxExecutionMetadata(long start, long end, long duration, boolean timeout, long output) {
        this(start, end, duration, timeout, output, null);
    }
}
