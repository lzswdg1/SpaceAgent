package com.spaceagent.platform.runtime.api;

/**
 * Public command for completing a run step.
 */
public record CompleteRunStepCommand(String agentRunId, String runStepId) {

    public CompleteRunStepCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(runStepId, "runStepId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
