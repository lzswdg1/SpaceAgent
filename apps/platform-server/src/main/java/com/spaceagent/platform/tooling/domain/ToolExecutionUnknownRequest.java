package com.spaceagent.platform.tooling.domain;

/**
 * Fenced transition from RUNNING to UNKNOWN after an indeterminate gateway outcome.
 */
public record ToolExecutionUnknownRequest(
        String agentRunId,
        String toolCallId,
        String claimToken,
        long expectedRevision,
        String reason,
        ToolExecutionReconciliationEvidence evidence) {

    public ToolExecutionUnknownRequest {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(toolCallId, "toolCallId");
        requireNonBlank(claimToken, "claimToken");
        requireNonBlank(reason, "reason");
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
