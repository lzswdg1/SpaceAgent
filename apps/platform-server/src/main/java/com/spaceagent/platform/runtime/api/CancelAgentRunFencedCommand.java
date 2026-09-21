package com.spaceagent.platform.runtime.api;

/** Terminal cancellation by the worker holding the current Run lease/fence. */
public record CancelAgentRunFencedCommand(
        String agentRunId, String leaseToken, long fencingToken, String reason) {
    public CancelAgentRunFencedCommand {
        if (agentRunId == null || agentRunId.isBlank()
                || leaseToken == null || leaseToken.isBlank()
                || fencingToken < 1
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "agentRunId, leaseToken, fencingToken and reason are required");
        }
    }
}
