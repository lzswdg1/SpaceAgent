package com.spaceagent.platform.runtime.api;

/**
 * Public command for recovering an interrupted run.
 */
public record RequestRecoveryCommand(String agentRunId, String reason) {

    public RequestRecoveryCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(reason, "reason");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
