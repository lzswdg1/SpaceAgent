package com.spaceagent.platform.runtime.api;

/**
 * Public command for accepting a pending handoff into a target run.
 */
public record AcceptHandoffCommand(String handoffId, String targetAgentRunId) {

    public AcceptHandoffCommand {
        requireNonBlank(handoffId, "handoffId");
        requireNonBlank(targetAgentRunId, "targetAgentRunId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
