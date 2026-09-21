package com.spaceagent.platform.tooling.domain;

/** Actual available samples, not configured limits or complete historical consumption. */
public record SandboxResourceMetrics(Long cpuUsageNanos, Long maxObservedMemoryBytes, Long networkRxBytes,
        Long networkTxBytes, Long workspaceApparentBytes, String coverage) {
    public SandboxResourceMetrics {
        cpuUsageNanos = valid(cpuUsageNanos); maxObservedMemoryBytes = valid(maxObservedMemoryBytes);
        networkRxBytes = valid(networkRxBytes); networkTxBytes = valid(networkTxBytes); workspaceApparentBytes = valid(workspaceApparentBytes);
        coverage = "RUNNING_CGROUP_SAMPLES_AND_POST_EXECUTION_APPARENT_BYTES; NOT_FULL_CPU_OR_PEAK_RSS_OR_ALLOCATED_DISK".equals(coverage)
                ? coverage : "UNAVAILABLE";
    }
    private static Long valid(Long value) { return value != null && value >= 0 ? value : null; }
}
