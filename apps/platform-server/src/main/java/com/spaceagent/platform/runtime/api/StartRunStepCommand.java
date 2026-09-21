package com.spaceagent.platform.runtime.api;

/**
 * Public command for opening the next step in an agent run.
 */
public record StartRunStepCommand(String agentRunId, String type) {

    public StartRunStepCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(type, "type");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
