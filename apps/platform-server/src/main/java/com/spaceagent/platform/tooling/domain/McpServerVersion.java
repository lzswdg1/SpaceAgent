package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpServerVersion(
        String id,
        String entryId,
        String version,
        McpCatalogSourceType sourceType,
        String sourceUri,
        String manifestSchemaUri,
        String manifestJson,
        String manifestSha256,
        McpAuthType authType,
        McpServerVersionState lifecycleState,
        Instant publishedAt,
        Instant createdAt) {
}
