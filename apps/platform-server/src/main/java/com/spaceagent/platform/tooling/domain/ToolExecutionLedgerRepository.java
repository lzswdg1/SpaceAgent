package com.spaceagent.platform.tooling.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for the tool execution ledger.
 */
public interface ToolExecutionLedgerRepository {
    Optional<ToolExecutionLedger> findById(String id);

    Optional<ToolExecutionLedger> findByRunIdAndToolCallId(String agentRunId, String toolCallId);

    List<ToolExecutionLedger> findByRunId(String agentRunId);

    ToolExecutionClaimDecision claim(ToolExecutionClaimRequest request);

    ToolExecutionTransitionResult complete(ToolExecutionCompletionRequest request);

    ToolExecutionTransitionResult markUnknown(ToolExecutionUnknownRequest request);

    ToolExecutionTransitionResult reconcileUnknown(ToolExecutionReconciliationRequest request);
}
