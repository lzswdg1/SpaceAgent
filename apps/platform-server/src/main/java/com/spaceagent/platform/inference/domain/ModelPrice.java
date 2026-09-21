package com.spaceagent.platform.inference.domain;

import java.time.Instant;

public record ModelPrice(
        String id,
        String tenantId,
        String providerModelId,
        int version,
        long inputMicrosPerMillionTokens,
        long outputMicrosPerMillionTokens,
        String currency,
        Instant effectiveFrom,
        Instant effectiveUntil,
        String createdBy,
        Instant createdAt) {
}
