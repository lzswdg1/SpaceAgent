package com.spaceagent.platform.tooling.domain;

/**
 * Persistence command for an atomic PostgreSQL-owned tool claim.
 */
public record ToolExecutionClaimRequest(
        String id,
        String agentRunId,
        String runStepId,
        String toolName,
        String toolCallId,
        String idempotencyKey,
        String arguments,
        String inputHash,
        String claimToken,
        String claimOwner,
        long leaseSeconds) {

    public ToolExecutionClaimRequest {
        requireNonBlank(id, "id");
        requireNonBlank(agentRunId, "agentRunId");
        requireNonBlank(runStepId, "runStepId");
        requireNonBlank(toolName, "toolName");
        requireNonBlank(toolCallId, "toolCallId");
        requireNonBlank(arguments, "arguments");
        requireNonBlank(inputHash, "inputHash");
        requireNonBlank(claimToken, "claimToken");
        requireNonBlank(claimOwner, "claimOwner");
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be positive");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
