package com.spaceagent.platform.tooling.domain;

/**
 * Fenced terminal write for a RUNNING tool claim.
 */
public record ToolExecutionCompletionRequest(
        String agentRunId,
        String toolCallId,
        String claimToken,
        long expectedRevision,
        ToolExecutionStatus status,
        String result,
        String resultRef,
        String error,
        ToolExecutionReconciliationEvidence lateEvidence) {

    public ToolExecutionCompletionRequest {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(toolCallId, "toolCallId");
        requireNonBlank(claimToken, "claimToken");
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        if (!isTerminal(status)) {
            throw new IllegalArgumentException("status must be terminal");
        }
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
