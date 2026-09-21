package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.inference.domain.ModelCallStatus;

public record CompleteModelCallCommand(
        String agentRunId,
        String logicalCallId,
        String claimToken,
        long expectedRevision,
        ModelCallStatus status,
        String providerRequestId,
        String responsePayload,
        String usagePayload,
        String errorCode,
        String errorSummary) {
}
