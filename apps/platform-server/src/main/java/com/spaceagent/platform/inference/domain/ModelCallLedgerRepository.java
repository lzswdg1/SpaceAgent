package com.spaceagent.platform.inference.domain;

import java.util.List;
import java.util.Optional;

public interface ModelCallLedgerRepository {

    ModelCallClaimDecision claim(ModelCallClaimRequest request);

    ModelCallTransitionResult complete(ModelCallCompletionRequest request);

    ModelCallTransitionResult recordFirstChunk(ModelCallFirstChunkRequest request);

    ModelCallTransitionResult markUnknown(ModelCallUnknownRequest request);

    Optional<ModelCallLedger> findByRunIdAndLogicalCallId(String agentRunId, String logicalCallId);

    List<ModelCallLedger> findByRunId(String agentRunId);
}
