package com.spaceagent.platform.inference.domain;

public record ModelCallUnknownRequest(
        String agentRunId,
        String logicalCallId,
        String claimToken,
        long expectedRevision,
        String errorCode,
        String errorSummary) {
}
