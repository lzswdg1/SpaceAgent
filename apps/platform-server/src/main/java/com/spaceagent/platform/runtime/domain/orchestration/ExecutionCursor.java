package com.spaceagent.platform.runtime.domain.orchestration;

/**
 * Execution/resume cursor reconstructed by Java, never Python in-memory state.
 */
public record ExecutionCursor(
        String phase,
        String stepId,
        String checkpointId,
        int checkpointSequence) {

    public ExecutionCursor {
        phase = phase == null || phase.isBlank() ? "planning" : phase;
        stepId = normalize(stepId);
        checkpointId = normalize(checkpointId);
        if (checkpointSequence < 0) {
            throw new IllegalArgumentException("checkpointSequence must not be negative");
        }
    }

    public static ExecutionCursor initial() {
        return new ExecutionCursor("planning", null, null, 0);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
