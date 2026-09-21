package com.spaceagent.platform.inference.api;

import java.time.Instant;

public record ProviderModelTestView(
        String providerId,
        String modelId,
        boolean success,
        int latencyMs,
        int inputTokens,
        int outputTokens,
        String responsePreview,
        String errorCode,
        Instant testedAt) {
}
