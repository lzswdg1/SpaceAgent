package com.spaceagent.platform.tooling.domain;

import java.time.Instant;

public record McpMarketplaceEntry(
        String id,
        String slug,
        String name,
        String description,
        McpTransportType transport,
        McpAuthType authType,
        String defaultEndpoint,
        String manifestJson,
        boolean enabled,
        String publisherNamespace,
        String registryName,
        McpCatalogSourceType sourceType,
        McpMarketplaceTrustTier trustTier,
        McpMarketplaceLifecycle lifecycle,
        String currentVersionId,
        String currentVersion,
        String currentManifestSha256,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
}
