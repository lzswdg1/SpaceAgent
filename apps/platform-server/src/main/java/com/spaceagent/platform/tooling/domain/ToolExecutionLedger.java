package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

/**
 * Durable audit/execution ledger entry for a tool invocation.
 *
 * <p>Persistence implementations enforce {@code UNIQUE(agentRunId, toolCallId)}.
 * A RUNNING entry is fenced by a claim token and monotonically increasing revision.
 * Terminal results may be replayed; an ambiguous outcome remains UNKNOWN until an
 * explicit reconciliation resolves it.
 */
public record ToolExecutionLedger(
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
        String claimToken,
        String claimOwner,
        Instant leaseUntil,
        long revision,
        Instant claimedAt,
        Instant updatedAt,
        ToolExecutionReconciliationEvidence reconciliationEvidence,
        Instant resolvedAt,
        String resolvedBy,
        String resolutionReason) {
}
