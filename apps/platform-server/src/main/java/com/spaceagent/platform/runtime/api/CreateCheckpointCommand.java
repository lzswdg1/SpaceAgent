package com.spaceagent.platform.runtime.api;

/**
 * Public command for persisting a runtime checkpoint.
 */
public record CreateCheckpointCommand(String agentRunId, String stateSnapshot) {

    public CreateCheckpointCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(stateSnapshot, "stateSnapshot");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
