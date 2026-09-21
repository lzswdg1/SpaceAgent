package com.spaceagent.platform.runtime.api;

/**
 * Public command for completing a run.
 */
public record CompleteAgentRunCommand(String agentRunId) {

    public CompleteAgentRunCommand {
        requireNonBlank(agentRunId, "agentRunId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
