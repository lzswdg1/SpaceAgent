package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.List;

public record McpRegistrySnapshot(
        String id,
        String sourceId,
        String syncJobId,
        String registryName,
        String registryVersion,
        McpRegistryStatus registryStatus,
        String statusMessage,
        String title,
        String description,
        String manifestSchemaUri,
        String repositoryUri,
        String manifestJson,
        String manifestSha256,
        McpRegistryCompatibility compatibility,
        String compatibilityReason,
        Instant sourcePublishedAt,
        Instant sourceUpdatedAt,
        Instant fetchedAt,
        List<RemoteTransport> transports) {

    public McpRegistrySnapshot {
        transports = transports == null ? List.of() : List.copyOf(transports);
    }

    public record RemoteTransport(
            String endpointUrl,
            String variablesJson,
            String headersJson,
            boolean secretHeaders) {
    }
}
