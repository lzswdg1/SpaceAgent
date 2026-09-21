package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.List;

public interface McpRegistryGateway {
    RegistryPage fetchPage(String baseUrl, Instant updatedSince, String cursor, int limit);

    record RegistryPage(List<RegistryServer> servers, String nextCursor) {
        public RegistryPage {
            servers = servers == null ? List.of() : List.copyOf(servers);
        }
    }

    record RegistryServer(
            String registryName,
            String registryVersion,
            McpRegistryStatus status,
            String statusMessage,
            String title,
            String description,
            String manifestSchemaUri,
            String repositoryUri,
            String sanitizedManifestJson,
            Instant publishedAt,
            Instant updatedAt,
            List<McpRegistrySnapshot.RemoteTransport> transports,
            McpRegistryCompatibility compatibility,
            String compatibilityReason) {

        public RegistryServer {
            transports = transports == null ? List.of() : List.copyOf(transports);
        }
    }
}
