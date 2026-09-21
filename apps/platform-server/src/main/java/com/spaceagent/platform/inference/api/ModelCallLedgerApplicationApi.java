package com.spaceagent.platform.inference.api;

import java.util.List;
import java.util.Optional;

/** Internal durable inference-call boundary; it is intentionally not exposed over HTTP. */
public interface ModelCallLedgerApplicationApi {

    ModelCallClaimView claim(ClaimModelCallCommand command);

    ModelCallTransitionView complete(CompleteModelCallCommand command);

    ModelCallTransitionView recordFirstChunk(RecordModelCallFirstChunkCommand command);

    ModelCallTransitionView markUnknown(MarkModelCallUnknownCommand command);

    List<ModelCallLedgerView> findByRunId(String agentRunId);

    Optional<ModelCallLedgerView> findByLogicalCall(String agentRunId, String logicalCallId);
}
