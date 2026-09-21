package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.List;

public record McpRegistryPublication(
        String publisherId,
        String publisherNamespace,
        String publisherDisplayName,
        String entryId,
        String slug,
        String registryName,
        String name,
        String description,
        String versionId,
        String version,
        String sourceUri,
        String manifestSchemaUri,
        String manifestJson,
        String manifestSha256,
        McpAuthType authType,
        McpMarketplaceLifecycle lifecycle,
        McpServerVersionState versionState,
        Instant sourcePublishedAt,
        Instant now,
        List<Transport> transports) {

    public McpRegistryPublication {
        transports = transports == null ? List.of() : List.copyOf(transports);
    }

    public record Transport(
            String id,
            int position,
            String endpointUrl,
            String variablesJson,
            String headersJson) {
    }

    public record Result(String entryId, String versionId, boolean created) {
    }
}
