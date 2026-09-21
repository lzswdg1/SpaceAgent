package com.spaceagent.platform.runtime.api;

/**
 * Public command for marking a run step failed.
 */
public record FailRunStepCommand(String agentRunId, String runStepId) {

    public FailRunStepCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(runStepId, "runStepId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
