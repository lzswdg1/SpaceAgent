package com.spaceagent.platform.runtime.api;

import java.util.Objects;

/**
 * Public command for accepting a reconstructed recovery state and resuming the run.
 */
public record AcceptRecoveryCommand(
        String agentRunId,
        String recoveryId,
        RecoveryResumeStateView resumeState) {

    public AcceptRecoveryCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(recoveryId, "recoveryId");
        Objects.requireNonNull(resumeState, "resumeState");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
