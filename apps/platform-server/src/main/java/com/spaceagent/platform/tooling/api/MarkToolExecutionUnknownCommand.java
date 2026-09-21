package com.spaceagent.platform.tooling.api;

/**
 * Public command for recording an ambiguous tool outcome that requires reconciliation
 * before the ledger may be reused.
 */
public record MarkToolExecutionUnknownCommand(
        String agentRunId,
        String toolCallId,
        String claimToken,
        long expectedRevision,
        String reason) {

    public MarkToolExecutionUnknownCommand {
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
