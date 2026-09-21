package com.spaceagent.platform.inference.domain;

public record ModelCallCompletionRequest(
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

    public ModelCallCompletionRequest {
        if (status == null || status == ModelCallStatus.RUNNING || status == ModelCallStatus.UNKNOWN) {
            throw new IllegalArgumentException("completion status must be terminal");
        }
    }
}
