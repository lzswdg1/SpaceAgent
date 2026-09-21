package com.spaceagent.platform.runtime.api;

/**
 * Public command for marking a run failed.
 */
public record FailAgentRunCommand(String agentRunId, String reason) {

    public FailAgentRunCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(reason, "reason");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
