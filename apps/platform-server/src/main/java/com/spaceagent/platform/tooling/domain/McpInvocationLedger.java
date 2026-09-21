package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

/**
 * Tooling-owned durable evidence for an MCP call that is not attached to an AgentRun.
 *
 * <p>The idempotency key is persisted only as a hash. An expired claim becomes UNKNOWN and
 * cannot be reused blindly.
 */
public record McpInvocationLedger(
        String id,
        String tenantId,
        String userId,
        String connectionId,
        String operationKey,
        String idempotencyKeyHash,
        String toolName,
        String argumentsJson,
        String inputHash,
        McpInvocationStatus status,
        String resultJson,
        String errorCode,
        String claimToken,
        String claimOwner,
        Instant leaseUntil,
        long revision,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt) {
    public McpInvocationLedger {
        if (id == null || id.isBlank() || tenantId == null || tenantId.isBlank()
                || userId == null || userId.isBlank() || connectionId == null
                || connectionId.isBlank() || operationKey == null || operationKey.isBlank()
                || idempotencyKeyHash == null || idempotencyKeyHash.length() != 64
                || toolName == null || toolName.isBlank() || argumentsJson == null
                || inputHash == null || inputHash.length() != 64 || status == null
                || claimOwner == null || claimOwner.isBlank() || leaseUntil == null
                || revision <= 0 || startedAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Invalid MCP invocation ledger");
        }
    }
}
