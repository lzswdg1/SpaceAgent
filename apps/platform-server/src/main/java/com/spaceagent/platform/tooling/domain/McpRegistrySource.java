package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpRegistrySource(
        String id,
        String sourceKey,
        String displayName,
        String baseUrl,
        boolean enabled,
        Instant lastSuccessfulSyncAt,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
}
