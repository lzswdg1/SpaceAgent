package com.spaceagent.platform.inference.domain;

public record ModelCallFirstChunkRequest(
        String agentRunId,
        String logicalCallId,
        String claimToken,
        long expectedRevision,
        long elapsedMillis) {
    public ModelCallFirstChunkRequest {
        if (elapsedMillis < 0 || elapsedMillis > 86_400_000) {
            throw new IllegalArgumentException("elapsedMillis is out of bounds");
        }
    }
}
