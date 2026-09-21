package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface McpMarketplaceRepository {
    List<McpMarketplaceEntry> findEntries();

    Optional<McpMarketplaceEntry> findEntry(String id);

    Optional<McpMarketplaceEntry> findEntryBySlug(String slug);

    Optional<McpMarketplaceEntry> findEntryByRegistryName(String registryName);

    List<McpServerVersion> findVersions(String entryId);

    Optional<McpServerVersion> findVersion(String id);

    Optional<McpServerVersion> findVersion(String entryId, String version);

    McpRegistryPublication.Result publishRegistryVersion(McpRegistryPublication publication);

    List<McpServerTransport> findTransports(String serverVersionId);

    List<McpInstallation> findInstallations(String tenantId, String userId);

    Optional<McpInstallation> findInstallation(String id);

    Optional<McpInstallation> findInstallation(
            String tenantId,
            String entryId,
            McpInstallationScope scope,
            String subjectId);

    void saveInstallation(McpInstallation value);

    List<McpConnection> findConnections(String tenantId, String userId);

    Optional<McpConnection> findConnection(String id);

    Optional<McpConnection> findConnectionByInstallation(String installationId);

    void saveConnection(McpConnection value);

    boolean saveConnectionIfRevision(McpConnection value, long expectedRevision);

    boolean refreshConnectionAuthorizationIfUnchanged(
            String connectionId,
            long expectedRevision,
            String expectedEncryptedAuthorization,
            String refreshedEncryptedAuthorization,
            Instant updatedAt);
}
