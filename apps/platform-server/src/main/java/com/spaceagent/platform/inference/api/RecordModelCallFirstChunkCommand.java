package com.spaceagent.platform.inference.api;

public record RecordModelCallFirstChunkCommand(
        String agentRunId,
        String logicalCallId,
        String claimToken,
        long expectedRevision,
        long elapsedMillis) {
}
