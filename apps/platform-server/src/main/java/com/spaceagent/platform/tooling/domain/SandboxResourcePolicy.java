package com.spaceagent.platform.tooling.domain;

import java.util.List;

/**
 * Isolation resource policy for one sandbox execution.
 */
public record SandboxResourcePolicy(
        int maxOutputBytes,
        int maxCpuSeconds,
        long maxMemoryBytes,
        boolean allowNetwork,
        List<String> allowedPaths,
        boolean readOnlyWorkspace) {

    public SandboxResourcePolicy {
        allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
    }

    public SandboxResourcePolicy(
            int maxOutputBytes,
            int maxCpuSeconds,
            long maxMemoryBytes,
            boolean allowNetwork,
            List<String> allowedPaths) {
        this(maxOutputBytes, maxCpuSeconds, maxMemoryBytes, allowNetwork, allowedPaths, false);
    }
}
