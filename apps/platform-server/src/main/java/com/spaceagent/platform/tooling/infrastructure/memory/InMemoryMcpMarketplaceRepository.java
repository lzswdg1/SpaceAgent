package com.spaceagent.platform.tooling.infrastructure.memory;

import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpCatalogSourceType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallation;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpMarketplaceEntry;
import com.spaceagent.platform.tooling.domain.McpMarketplaceLifecycle;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpMarketplaceTrustTier;
import com.spaceagent.platform.tooling.domain.McpRegistryPublication;
import com.spaceagent.platform.tooling.domain.McpRegistryPublicationConflictException;
import com.spaceagent.platform.tooling.domain.McpServerTransport;
import com.spaceagent.platform.tooling.domain.McpServerVersion;
import com.spaceagent.platform.tooling.domain.McpServerVersionState;
import com.spaceagent.platform.tooling.domain.McpTransportType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryMcpMarketplaceRepository implements McpMarketplaceRepository {
    private static final String GITHUB_ENTRY_ID = "26000000-0000-4000-8000-000000000001";
    private static final String CUSTOM_ENTRY_ID = "26000000-0000-4000-8000-000000000002";
    private static final String GITHUB_VERSION_ID = "10390000-0000-4000-8000-000000000101";
    private static final String CUSTOM_VERSION_ID = "10390000-0000-4000-8000-000000000102";
    private static final String GITHUB_MANIFEST = """
            {"oauthProfile":"host_oauth_2_1_pkce","accountTool":"get_me",\
            "repositoryTool":"search_repositories","checkoutGrant":"host_ephemeral_bearer",\
            "accountLogin":true,"publicUrlDiscovery":true}
            """.replace("\n", "");
    private static final String CUSTOM_MANIFEST = "{}";

    private final Map<String, McpMarketplaceEntry> entries = new LinkedHashMap<>();
    private final Map<String, McpServerVersion> versions = new LinkedHashMap<>();
    private final Map<String, McpServerTransport> transports = new LinkedHashMap<>();
    private final Map<String, McpInstallation> installations = new ConcurrentHashMap<>();
    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    public InMemoryMcpMarketplaceRepository() {
        Instant now = Instant.now();
        addSeed(
                GITHUB_ENTRY_ID, "github", "GitHub", "GitHub through MCP", "com.github",
                GITHUB_VERSION_ID, McpAuthType.OAUTH2, GithubMcpProfiles.OFFICIAL_REMOTE_ENDPOINT,
                false, GITHUB_MANIFEST, "https://github.com/github/github-mcp-server", now);
        addSeed(
                CUSTOM_ENTRY_ID, "custom-streamable-http", "Custom MCP",
                "Custom Streamable HTTP MCP", "ai.spaceagent.local", CUSTOM_VERSION_ID,
                McpAuthType.CUSTOM, null, true, CUSTOM_MANIFEST, null, now);
    }

    @Override
    public List<McpMarketplaceEntry> findEntries() {
        return entries.values().stream()
                .filter(McpMarketplaceEntry::enabled)
                .filter(value -> value.lifecycle() != McpMarketplaceLifecycle.REVOKED)
                .sorted(Comparator.comparing(McpMarketplaceEntry::name)
                        .thenComparing(McpMarketplaceEntry::id))
                .toList();
    }

    @Override
    public Optional<McpMarketplaceEntry> findEntry(String id) {
        return Optional.ofNullable(entries.get(id))
                .filter(McpMarketplaceEntry::enabled)
                .filter(value -> value.lifecycle() != McpMarketplaceLifecycle.REVOKED);
    }

    @Override
    public Optional<McpMarketplaceEntry> findEntryBySlug(String slug) {
        return findEntries().stream().filter(entry -> entry.slug().equals(slug)).findFirst();
    }

    @Override
    public Optional<McpMarketplaceEntry> findEntryByRegistryName(String registryName) {
        return entries.values().stream()
                .filter(entry -> registryName.equals(entry.registryName()))
                .findFirst();
    }

    @Override
    public List<McpServerVersion> findVersions(String entryId) {
        return versions.values().stream()
                .filter(version -> version.entryId().equals(entryId))
                .sorted(Comparator.comparing(McpServerVersion::createdAt).reversed()
                        .thenComparing(McpServerVersion::id))
                .limit(100)
                .toList();
    }

    @Override
    public Optional<McpServerVersion> findVersion(String id) {
        return Optional.ofNullable(versions.get(id));
    }

    @Override
    public Optional<McpServerVersion> findVersion(String entryId, String version) {
        return versions.values().stream()
                .filter(value -> value.entryId().equals(entryId) && value.version().equals(version))
                .findFirst();
    }

    @Override
    public synchronized McpRegistryPublication.Result publishRegistryVersion(
            McpRegistryPublication value) {
        McpMarketplaceEntry existing = findEntryByRegistryName(value.registryName()).orElse(null);
        String entryId = existing == null ? value.entryId() : existing.id();
        McpServerVersion current = findVersion(entryId, value.version()).orElse(null);
        if (current != null && !current.manifestSha256().equals(value.manifestSha256())) {
            throw new McpRegistryPublicationConflictException(
                    "An immutable marketplace version already exists with a different manifest");
        }
        boolean created = current == null;
        if (created) {
            current = new McpServerVersion(value.versionId(), entryId, value.version(),
                    McpCatalogSourceType.OFFICIAL_REGISTRY, value.sourceUri(),
                    value.manifestSchemaUri(), value.manifestJson(), value.manifestSha256(),
                    value.authType(), value.versionState(), value.sourcePublishedAt(), value.now());
            versions.put(current.id(), current);
            for (McpRegistryPublication.Transport transport : value.transports()) {
                transports.put(transport.id(), new McpServerTransport(
                        transport.id(), current.id(), transport.position(),
                        McpTransportType.STREAMABLE_HTTP, transport.endpointUrl(), false,
                        transport.variablesJson(), transport.headersJson(), true, value.now()));
            }
        }
        entries.put(entryId, new McpMarketplaceEntry(
                entryId, existing == null ? value.slug() : existing.slug(), value.name(),
                value.description(), McpTransportType.STREAMABLE_HTTP, value.authType(),
                value.transports().getFirst().endpointUrl(), value.manifestJson(), true,
                value.publisherNamespace(), value.registryName(),
                McpCatalogSourceType.OFFICIAL_REGISTRY,
                McpMarketplaceTrustTier.REGISTRY_VERIFIED, value.lifecycle(), current.id(),
                current.version(), current.manifestSha256(), existing == null ? 1 : existing.revision() + 1,
                existing == null ? value.now() : existing.createdAt(), value.now()));
        return new McpRegistryPublication.Result(entryId, current.id(), created);
    }

    @Override
    public List<McpServerTransport> findTransports(String serverVersionId) {
        return transports.values().stream()
                .filter(transport -> transport.serverVersionId().equals(serverVersionId))
                .sorted(Comparator.comparingInt(McpServerTransport::position)
                        .thenComparing(McpServerTransport::id))
                .limit(10)
                .toList();
    }

    @Override
    public List<McpInstallation> findInstallations(String tenantId, String userId) {
        return installations.values().stream()
                .filter(installation -> installation.tenantId().equals(tenantId))
                .filter(installation -> installation.scope() == McpInstallationScope.ORGANIZATION
                        || installation.subjectId().equals(userId))
                .sorted(Comparator.comparing(McpInstallation::createdAt)
                        .thenComparing(McpInstallation::id))
                .toList();
    }

    @Override
    public Optional<McpInstallation> findInstallation(String id) {
        return Optional.ofNullable(installations.get(id));
    }

    @Override
    public Optional<McpInstallation> findInstallation(
            String tenantId,
            String entryId,
            McpInstallationScope scope,
            String subjectId) {
        return installations.values().stream()
                .filter(installation -> installation.tenantId().equals(tenantId)
                        && installation.entryId().equals(entryId)
                        && installation.scope() == scope
                        && installation.subjectId().equals(subjectId))
                .findFirst();
    }

    @Override
    public void saveInstallation(McpInstallation value) {
        installations.put(value.id(), value);
    }

    @Override
    public List<McpConnection> findConnections(String tenantId, String userId) {
        return connections.values().stream()
                .filter(connection -> connection.tenantId().equals(tenantId))
                .filter(connection -> findInstallation(connection.installationId())
                        .map(installation -> installation.scope()
                                == McpInstallationScope.ORGANIZATION
                                || installation.subjectId().equals(userId))
                        .orElse(false))
                .sorted(Comparator.comparing(McpConnection::createdAt)
                        .thenComparing(McpConnection::id))
                .toList();
    }

    @Override
    public Optional<McpConnection> findConnection(String id) {
        return Optional.ofNullable(connections.get(id));
    }

    @Override
    public Optional<McpConnection> findConnectionByInstallation(String installationId) {
        return connections.values().stream()
                .filter(connection -> connection.installationId().equals(installationId))
                .findFirst();
    }

    @Override
    public void saveConnection(McpConnection value) {
        connections.put(value.id(), value);
    }

    @Override
    public synchronized boolean saveConnectionIfRevision(
            McpConnection value, long expectedRevision) {
        McpConnection current = connections.get(value.id());
        if (current == null || current.revision() != expectedRevision) return false;
        connections.put(value.id(), value);
        return true;
    }

    @Override
    public synchronized boolean refreshConnectionAuthorizationIfUnchanged(
            String connectionId,
            long expectedRevision,
            String expectedEncryptedAuthorization,
            String refreshedEncryptedAuthorization,
            Instant updatedAt) {
        McpConnection current = connections.get(connectionId);
        if (current == null || current.revision() != expectedRevision
                || !current.encryptedAuthJson().equals(expectedEncryptedAuthorization)
                || current.state() == McpConnectionState.REVOKED) {
            return false;
        }
        connections.put(connectionId, new McpConnection(
                current.id(), current.installationId(), current.tenantId(), current.managedBy(),
                current.endpointUrl(), refreshedEncryptedAuthorization, current.authType(),
                current.state(), current.externalAccountId(), current.externalAccountName(),
                current.revision(), current.createdAt(), updatedAt, current.revokedAt()));
        return true;
    }

    private void addSeed(
            String entryId,
            String slug,
            String name,
            String description,
            String publisher,
            String versionId,
            McpAuthType authType,
            String endpoint,
            boolean endpointConfigurable,
            String manifest,
            String sourceUri,
            Instant now) {
        String digest = sha256(manifest);
        entries.put(entryId, new McpMarketplaceEntry(
                entryId, slug, name, description, McpTransportType.STREAMABLE_HTTP,
                authType, endpoint, manifest, true, publisher, null,
                McpCatalogSourceType.BUILT_IN, McpMarketplaceTrustTier.PLATFORM_CURATED,
                McpMarketplaceLifecycle.ACTIVE, versionId, "1.0.0", digest, 1, now, now));
        versions.put(versionId, new McpServerVersion(
                versionId, entryId, "1.0.0", McpCatalogSourceType.BUILT_IN, sourceUri,
                null, manifest, digest, authType, McpServerVersionState.APPROVED, now, now));
        String transportId = versionId.endsWith("101")
                ? "10390000-0000-4000-8000-000000000201"
                : "10390000-0000-4000-8000-000000000202";
        transports.put(transportId, new McpServerTransport(
                transportId, versionId, 0, McpTransportType.STREAMABLE_HTTP, endpoint,
                endpointConfigurable, "{}", "{}", true, now));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash MCP manifest", error);
        }
    }
}
