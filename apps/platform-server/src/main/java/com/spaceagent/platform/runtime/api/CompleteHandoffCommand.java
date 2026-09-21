package com.spaceagent.platform.runtime.api;

/**
 * Public command for completing an accepted handoff.
 */
public record CompleteHandoffCommand(String handoffId) {

    public CompleteHandoffCommand {
        requireNonBlank(handoffId, "handoffId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
