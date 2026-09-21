package com.spaceagent.platform.tooling.domain;

import java.util.Objects;

/**
 * Explicit UNKNOWN-to-terminal reconciliation command.
 */
public record ToolExecutionReconciliationRequest(
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

    public ToolExecutionReconciliationRequest {
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
