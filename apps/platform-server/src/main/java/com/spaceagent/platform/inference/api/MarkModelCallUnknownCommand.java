package com.spaceagent.platform.inference.api;

public record MarkModelCallUnknownCommand(
        String agentRunId,
        String logicalCallId,
        String claimToken,
        long expectedRevision,
        String errorCode,
        String errorSummary) {
}
