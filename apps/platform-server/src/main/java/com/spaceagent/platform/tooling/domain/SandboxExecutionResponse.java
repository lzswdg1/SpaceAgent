package com.spaceagent.platform.tooling.domain;

import java.util.List;

/**
 * Structured result of one sandbox execution.
 */
public record SandboxExecutionResponse(
        String executionId,
        String agentRunId,
        String toolCallId,
        int exitStatus,
        SandboxExecutionStatus status,
        String stdout,
        String stderr,
        List<String> artifactRefs,
        SandboxExecutionMetadata metadata,
        String error) {

    public SandboxExecutionResponse {
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
    }
}
