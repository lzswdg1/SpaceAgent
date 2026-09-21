package com.spaceagent.platform.tooling.api;

import java.util.List;
import java.util.Optional;

/**
 * Public tool execution ledger application API. Runtime can call this port without
 * reaching into the tooling persistence implementation.
 */
public interface ToolExecutionLedgerApplicationApi {

    ToolExecutionClaimView claim(ClaimToolExecutionCommand command);

    ToolExecutionTransitionView markUnknown(MarkToolExecutionUnknownCommand command);

    ToolExecutionTransitionView complete(CompleteToolExecutionCommand command);

    ToolExecutionTransitionView reconcileUnknown(ReconcileUnknownToolExecutionCommand command);

    Optional<ToolExecutionLedgerView> findById(String id);

    List<ToolExecutionLedgerView> findByRunId(String agentRunId);
}
