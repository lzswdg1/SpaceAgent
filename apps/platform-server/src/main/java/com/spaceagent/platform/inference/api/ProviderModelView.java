package com.spaceagent.platform.inference.api;

import java.time.Instant;

/**
 * Public provider model query result.
 */
public record ProviderModelView(
        String id,
        String providerId,
        String modelId,
        String displayName,
        int maxContextTokens,
        boolean isDefault,
        Instant createdAt) {
}
