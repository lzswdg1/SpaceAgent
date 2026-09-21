package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationEvidence;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;

import java.util.Objects;

/**
 * Internal-only command for explicitly resolving an UNKNOWN tool outcome.
 */
public record ReconcileUnknownToolExecutionCommand(
        String agentRunId,
        String toolCallId,
        String inputHash,
        long expectedRevision,
        ToolExecutionStatus resolution,
        String result,
        String resultRef,
        String error,
        ToolExecutionReconciliationEvidence evidence,
        String resolvedBy,
        String resolutionReason) {

    public ReconcileUnknownToolExecutionCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(toolCallId, "toolCallId");
        requireNonBlank(inputHash, "inputHash");
        requireNonBlank(resolvedBy, "resolvedBy");
        requireNonBlank(resolutionReason, "resolutionReason");
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        if (!isTerminal(resolution)) {
            throw new IllegalArgumentException("resolution must be terminal");
        }
        Objects.requireNonNull(evidence, "evidence");
    }

    private static boolean isTerminal(ToolExecutionStatus status) {
        return status == ToolExecutionStatus.SUCCEEDED
                || status == ToolExecutionStatus.FAILED
                || status == ToolExecutionStatus.TIMED_OUT
                || status == ToolExecutionStatus.CANCELLED;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
