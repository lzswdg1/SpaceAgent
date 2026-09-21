package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;

/**
 * Public command for writing the terminal result of a tool execution.
 */
public record CompleteToolExecutionCommand(
        String agentRunId,
        String toolCallId,
        String claimToken,
        long expectedRevision,
        ToolExecutionStatus status,
        String result,
        String resultRef,
        String error) {

    public CompleteToolExecutionCommand {
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
