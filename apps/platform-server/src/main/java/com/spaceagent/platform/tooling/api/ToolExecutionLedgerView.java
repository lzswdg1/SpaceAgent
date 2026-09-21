package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;

import java.time.Instant;

/**
 * Public tool-execution ledger query result.
 */
public record ToolExecutionLedgerView(
        String id,
        String agentRunId,
        String runStepId,
        String toolName,
        String toolCallId,
        String idempotencyKey,
        String arguments,
        String inputHash,
        ToolExecutionStatus status,
        String result,
        String resultRef,
        String error,
        Instant startedAt,
        Instant completedAt,
        String claimOwner,
        Instant leaseUntil,
        long revision,
        Instant claimedAt,
        Instant updatedAt,
        Instant resolvedAt,
        String resolvedBy,
        String resolutionReason) {
}
