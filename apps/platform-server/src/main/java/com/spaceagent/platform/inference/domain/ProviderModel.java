package com.spaceagent.platform.inference.domain;

import java.time.Instant;

/**
 * A model exposed by a {@link ModelProvider}.
 */
public record ProviderModel(
        String id,
        String providerId,
        String modelId,
        String displayName,
        int maxContextTokens,
        boolean isDefault,
        Instant createdAt) {
}
