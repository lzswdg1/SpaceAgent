package com.spaceagent.platform.runtime.api;

/** Advances the durable cursor without relying on process-local graph state. */
public record AdvanceExecutionCursorCommand(
        String agentRunId,
        String phase,
        String stepId) {

    public AdvanceExecutionCursorCommand {
        if (agentRunId == null || agentRunId.isBlank()) {
            throw new IllegalArgumentException("agentRunId is required");
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("phase is required");
        }
        stepId = stepId == null || stepId.isBlank() ? null : stepId.trim();
    }
}
