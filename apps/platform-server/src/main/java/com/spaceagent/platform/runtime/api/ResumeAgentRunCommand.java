package com.spaceagent.platform.runtime.api;

/** Fenced resume command used only by a worker holding the current Run lease. */
public record ResumeAgentRunCommand(
        String agentRunId,
        String leaseToken,
        long fencingToken) {

    public ResumeAgentRunCommand {
        if (agentRunId == null || agentRunId.isBlank()
                || leaseToken == null || leaseToken.isBlank()
                || fencingToken < 1) {
            throw new IllegalArgumentException("agentRunId, leaseToken and fencingToken are required");
        }
    }
}
