package com.spaceagent.platform.tooling.api;

/**
 * Internal application command for atomically claiming one logical tool call.
 */
public record ClaimToolExecutionCommand(
        String agentRunId,
        String runStepId,
        String toolName,
        String toolCallId,
        String idempotencyKey,
        String arguments,
        String inputHash,
        long leaseSeconds) {

    public ClaimToolExecutionCommand {
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(runStepId, "runStepId");
        requireNonBlank(toolName, "toolName");
        requireNonBlank(toolCallId, "toolCallId");
        idempotencyKey = normalizeOptional(idempotencyKey);
        requireNonBlank(arguments, "arguments");
        requireNonBlank(inputHash, "inputHash");
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be positive");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
