package com.spaceagent.platform.inference.api;

public record ClaimModelCallCommand(
        String agentRunId,
        String runStepId,
        String logicalCallId,
        String requestHash,
        String providerId,
        String modelId,
        long leaseSeconds) {
}
