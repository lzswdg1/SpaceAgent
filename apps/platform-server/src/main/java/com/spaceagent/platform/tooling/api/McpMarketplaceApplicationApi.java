package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpCatalogSourceType;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpInstallationState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceLifecycle;
import com.spaceagent.platform.tooling.domain.McpMarketplaceTrustTier;
import com.spaceagent.platform.tooling.domain.McpServerVersionState;
import com.spaceagent.platform.tooling.domain.McpTransportType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface McpMarketplaceApplicationApi {
    List<EntryView> catalog();

    List<ServerVersionView> versions(String entryId);

    ServerVersionView version(String entryId, String versionId);

    List<InstallationView> installations(String tenantId, String userId);

    InstallationView install(InstallCommand command);

    InstallationView disable(MutateCommand command);

    ConnectionView connect(ConnectCommand command);

    ConnectionView revokeConnection(MutateCommand command);

    List<ConnectionView> connections(String tenantId, String userId);

    record InstallCommand(
            String tenantId,
            String userId,
            String entryId,
            String serverVersionId,
            McpInstallationScope scope,
            String displayName) {
        public InstallCommand(
                String tenantId,
                String userId,
                String entryId,
                McpInstallationScope scope,
                String displayName) {
            this(tenantId, userId, entryId, null, scope, displayName);
        }
    }

    record MutateCommand(String tenantId, String userId, String id) {
    }

    record ConnectCommand(
            String tenantId,
            String userId,
            String installationId,
            String endpointUrl,
            McpAuthType authType,
            Map<String, String> auth) {
    }

    record EntryView(
            String id,
            String slug,
            String name,
            String description,
            McpTransportType transport,
            McpAuthType authType,
            String defaultEndpoint,
            String manifestJson,
            String publisherNamespace,
            String registryName,
            McpCatalogSourceType sourceType,
            McpMarketplaceTrustTier trustTier,
            McpMarketplaceLifecycle lifecycle,
            String currentVersionId,
            String currentVersion,
            String currentManifestSha256,
            long revision) {
    }

    record ServerVersionView(
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
            Instant createdAt,
            List<TransportView> transports) {
        public ServerVersionView {
            transports = transports == null ? List.of() : List.copyOf(transports);
        }
    }

    record TransportView(
            String id,
            int position,
            McpTransportType transportType,
            String endpointTemplate,
            boolean endpointConfigurable,
            String variablesSchemaJson,
            String headersSchemaJson,
            boolean enabled) {
    }

    record InstallationView(
            String id,
            String entryId,
            String serverVersionId,
            String serverVersion,
            String tenantId,
            String subjectId,
            String createdBy,
            McpInstallationScope scope,
            String displayName,
            McpInstallationState state,
            Instant createdAt,
            Instant updatedAt) {
    }

    record ConnectionView(
            String id,
            String installationId,
            String tenantId,
            String managedBy,
            String endpointUrl,
            McpAuthType authType,
            McpConnectionState state,
            boolean authConfigured,
            String externalAccountId,
            String externalAccountName,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant revokedAt) {
    }
}
