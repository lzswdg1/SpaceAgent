package com.spaceagent.platform.inference.domain;

public record ModelCallClaimRequest(
        String id,
        String agentRunId,
        String runStepId,
        String logicalCallId,
        String requestHash,
        String providerId,
        String modelId,
        String claimToken,
        String claimOwner,
        long leaseSeconds) {

    public ModelCallClaimRequest {
        if (leaseSeconds <= 0 || leaseSeconds > 720) {
            throw new IllegalArgumentException("leaseSeconds must be between 1 and 720");
        }
    }
}
