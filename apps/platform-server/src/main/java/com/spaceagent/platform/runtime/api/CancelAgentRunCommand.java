package com.spaceagent.platform.runtime.api;

/**
 * Public command for cancelling a run and writing an explicit terminal state.
 */
public record CancelAgentRunCommand(String agentRunId, String reason) {

    public CancelAgentRunCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(reason, "reason");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
