package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.inference.domain.ModelCallStatus;

import java.time.Instant;

public record ModelCallLedgerView(
        String id,
        String agentRunId,
        String runStepId,
        String logicalCallId,
        String requestHash,
        ModelCallStatus status,
        String providerId,
        String modelId,
        String claimOwner,
        Instant leaseUntil,
        long revision,
        Instant claimedAt,
        Instant firstChunkAt,
        Long firstChunkMillis,
        String providerRequestId,
        String responsePayload,
        String usagePayload,
        String errorCode,
        String errorSummary,
        Instant createdAt,
        Instant updatedAt) {
}
