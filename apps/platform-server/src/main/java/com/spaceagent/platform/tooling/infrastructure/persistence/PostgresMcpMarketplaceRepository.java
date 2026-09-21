package com.spaceagent.platform.tooling.infrastructure.persistence;

import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpCatalogSourceType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallation;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpInstallationState;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresMcpMarketplaceRepository implements McpMarketplaceRepository {
    private static final String ENTRY_SELECT = """
            SELECT entry.*, publisher.namespace AS publisher_namespace,
                   version.version AS current_version,
                   version.manifest_sha256 AS current_manifest_sha256
              FROM platform_mcp_marketplace_entries entry
              JOIN platform_mcp_publishers publisher ON publisher.id = entry.publisher_id
              JOIN platform_mcp_server_versions version ON version.id = entry.current_version_id
            """;
    private static final String INSTALLATION_SELECT = """
            SELECT installation.*, version.version AS server_version
              FROM platform_mcp_installations installation
              JOIN platform_mcp_server_versions version
                ON version.id = installation.server_version_id
            """;

    private final JdbcTemplate jdbc;

    public PostgresMcpMarketplaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<McpMarketplaceEntry> findEntries() {
        return jdbc.query(ENTRY_SELECT + """
                 WHERE entry.enabled AND entry.lifecycle_state <> 'REVOKED'
                 ORDER BY entry.name, entry.id
                """, this::entry);
    }

    @Override
    public Optional<McpMarketplaceEntry> findEntry(String id) {
        return jdbc.query(ENTRY_SELECT + """
                 WHERE entry.id = CAST(? AS UUID) AND entry.enabled
                   AND entry.lifecycle_state <> 'REVOKED'
                """, this::entry, id).stream().findFirst();
    }

    @Override
    public Optional<McpMarketplaceEntry> findEntryBySlug(String slug) {
        return jdbc.query(ENTRY_SELECT + """
                 WHERE entry.slug = ? AND entry.enabled
                   AND entry.lifecycle_state <> 'REVOKED'
                """, this::entry, slug).stream().findFirst();
    }

    @Override
    public Optional<McpMarketplaceEntry> findEntryByRegistryName(String registryName) {
        return jdbc.query(ENTRY_SELECT + """
                 WHERE entry.registry_name = ? AND entry.source_type = 'OFFICIAL_REGISTRY'
                """, this::entry, registryName).stream().findFirst();
    }

    @Override
    public List<McpServerVersion> findVersions(String entryId) {
        return jdbc.query("""
                SELECT * FROM platform_mcp_server_versions
                 WHERE entry_id = CAST(? AS UUID)
                 ORDER BY created_at DESC, id
                 LIMIT 100
                """, this::version, entryId);
    }

    @Override
    public Optional<McpServerVersion> findVersion(String id) {
        return jdbc.query("""
                SELECT * FROM platform_mcp_server_versions WHERE id = CAST(? AS UUID)
                """, this::version, id).stream().findFirst();
    }

    @Override
    public Optional<McpServerVersion> findVersion(String entryId, String version) {
        return jdbc.query("""
                SELECT * FROM platform_mcp_server_versions
                 WHERE entry_id = CAST(? AS UUID) AND version = ?
                """, this::version, entryId, version).stream().findFirst();
    }

    @Override
    @Transactional(noRollbackFor = McpRegistryPublicationConflictException.class)
    public McpRegistryPublication.Result publishRegistryVersion(McpRegistryPublication value) {
        Optional<McpMarketplaceEntry> existingEntry = findEntryByRegistryName(value.registryName());
        String entryId = existingEntry.map(McpMarketplaceEntry::id).orElse(value.entryId());
        Optional<McpServerVersion> existingVersion = findVersion(entryId, value.version());
        if (existingVersion.isPresent()
                && !existingVersion.orElseThrow().manifestSha256().equals(value.manifestSha256())) {
            throw new McpRegistryPublicationConflictException(
                    "An immutable marketplace version already exists with a different manifest");
        }
        jdbc.update("""
                INSERT INTO platform_mcp_publishers(
                    id, namespace, display_name, source_type, verification_state,
                    created_at, updated_at)
                VALUES(CAST(? AS UUID), ?, ?, 'OFFICIAL_REGISTRY', 'REGISTRY_VERIFIED', ?, ?)
                ON CONFLICT(namespace) DO NOTHING
                """, value.publisherId(), value.publisherNamespace(),
                value.publisherDisplayName(), Timestamp.from(value.now()), Timestamp.from(value.now()));
        String publisherId = jdbc.queryForObject(
                "SELECT id::text FROM platform_mcp_publishers WHERE namespace = ?",
                String.class, value.publisherNamespace());
        if (existingVersion.isEmpty()) {
            if (existingEntry.isEmpty()) {
                jdbc.update("""
                        INSERT INTO platform_mcp_marketplace_entries(
                            id, slug, name, description, transport, auth_type, default_endpoint,
                            manifest_json, enabled, created_at, updated_at, publisher_id,
                            registry_name, source_type, trust_tier, lifecycle_state,
                            current_version_id, revision)
                        VALUES(CAST(? AS UUID), ?, ?, ?, 'STREAMABLE_HTTP', ?, ?, CAST(? AS JSONB),
                            TRUE, ?, ?, CAST(? AS UUID), ?, 'OFFICIAL_REGISTRY',
                            'REGISTRY_VERIFIED', ?, CAST(? AS UUID), 1)
                        """, entryId, value.slug(), value.name(), value.description(),
                        value.authType().name(), value.transports().getFirst().endpointUrl(),
                        value.manifestJson(), Timestamp.from(value.now()), Timestamp.from(value.now()),
                        publisherId, value.registryName(), value.lifecycle().name(), value.versionId());
            }
            jdbc.update("""
                    INSERT INTO platform_mcp_server_versions(
                        id, entry_id, version, source_type, source_uri, manifest_schema_uri,
                        manifest_json, manifest_sha256, auth_type, lifecycle_state,
                        published_at, created_at)
                    VALUES(CAST(? AS UUID), CAST(? AS UUID), ?, 'OFFICIAL_REGISTRY', ?, ?,
                        CAST(? AS JSONB), ?, ?, ?, ?, ?)
                    """, value.versionId(), entryId, value.version(), value.sourceUri(),
                    value.manifestSchemaUri(), value.manifestJson(), value.manifestSha256(),
                    value.authType().name(), value.versionState().name(),
                    timestamp(value.sourcePublishedAt()), Timestamp.from(value.now()));
            for (McpRegistryPublication.Transport transport : value.transports()) {
                jdbc.update("""
                        INSERT INTO platform_mcp_server_transports(
                            id, server_version_id, position, transport_type, endpoint_template,
                            endpoint_configurable, variables_schema_json, headers_schema_json,
                            enabled, created_at)
                        VALUES(CAST(? AS UUID), CAST(? AS UUID), ?, 'STREAMABLE_HTTP', ?, FALSE,
                            CAST(? AS JSONB), CAST(? AS JSONB), TRUE, ?)
                        """, transport.id(), value.versionId(), transport.position(),
                        transport.endpointUrl(), transport.variablesJson(), transport.headersJson(),
                        Timestamp.from(value.now()));
            }
        }

        String versionId = existingVersion.map(McpServerVersion::id).orElse(value.versionId());
        jdbc.update("""
                UPDATE platform_mcp_marketplace_entries
                   SET name = ?, description = ?, auth_type = ?, default_endpoint = ?,
                       manifest_json = CAST(? AS JSONB), enabled = TRUE,
                       publisher_id = CAST(? AS UUID), trust_tier = 'REGISTRY_VERIFIED',
                       lifecycle_state = ?, current_version_id = CAST(? AS UUID),
                       revision = revision + 1, updated_at = ?
                 WHERE id = CAST(? AS UUID)
                """, value.name(), value.description(), value.authType().name(),
                value.transports().getFirst().endpointUrl(), value.manifestJson(), publisherId,
                value.lifecycle().name(), versionId, Timestamp.from(value.now()), entryId);
        return new McpRegistryPublication.Result(entryId, versionId, existingVersion.isEmpty());
    }

    @Override
    public List<McpServerTransport> findTransports(String serverVersionId) {
        return jdbc.query("""
                SELECT * FROM platform_mcp_server_transports
                 WHERE server_version_id = CAST(? AS UUID)
                 ORDER BY position, id
                 LIMIT 10
                """, this::transport, serverVersionId);
    }

    @Override
    public List<McpInstallation> findInstallations(String tenantId, String userId) {
        return jdbc.query(INSTALLATION_SELECT + """
                 WHERE installation.tenant_id = ?
                   AND (installation.scope = 'ORGANIZATION' OR installation.subject_id = ?)
                   AND installation.state <> 'REMOVED'
                 ORDER BY installation.created_at, installation.id
                """, this::installation, tenantId, userId);
    }

    @Override
    public Optional<McpInstallation> findInstallation(String id) {
        return jdbc.query(INSTALLATION_SELECT + """
                 WHERE installation.id = CAST(? AS UUID)
                """, this::installation, id).stream().findFirst();
    }

    @Override
    public Optional<McpInstallation> findInstallation(
            String tenantId,
            String entryId,
            McpInstallationScope scope,
            String subjectId) {
        return jdbc.query(INSTALLATION_SELECT + """
                 WHERE installation.tenant_id = ?
                   AND installation.entry_id = CAST(? AS UUID)
                   AND installation.scope = ? AND installation.subject_id = ?
                """, this::installation, tenantId, entryId, scope.name(), subjectId)
                .stream().findFirst();
    }

    @Override
    public void saveInstallation(McpInstallation value) {
        jdbc.update("""
                INSERT INTO platform_mcp_installations(
                    id, entry_id, server_version_id, tenant_id, subject_id, created_by,
                    scope, display_name, state, created_at, updated_at)
                VALUES(CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    state = EXCLUDED.state,
                    updated_at = EXCLUDED.updated_at
                """, value.id(), value.entryId(), value.serverVersionId(), value.tenantId(),
                value.subjectId(), value.createdBy(), value.scope().name(), value.displayName(),
                value.state().name(), Timestamp.from(value.createdAt()),
                Timestamp.from(value.updatedAt()));
    }

    @Override
    public List<McpConnection> findConnections(String tenantId, String userId) {
        return jdbc.query("""
                SELECT connection.*
                  FROM platform_mcp_connections connection
                  JOIN platform_mcp_installations installation
                    ON installation.id = connection.installation_id
                 WHERE connection.tenant_id = ?
                   AND (installation.scope = 'ORGANIZATION' OR installation.subject_id = ?)
                 ORDER BY connection.created_at, connection.id
                """, this::connection, tenantId, userId);
    }

    @Override
    public Optional<McpConnection> findConnection(String id) {
        return jdbc.query("""
                SELECT * FROM platform_mcp_connections WHERE id = CAST(? AS UUID)
                """, this::connection, id).stream().findFirst();
    }

    @Override
    public Optional<McpConnection> findConnectionByInstallation(String installationId) {
        return jdbc.query("""
                SELECT * FROM platform_mcp_connections
                 WHERE installation_id = CAST(? AS UUID)
                """, this::connection, installationId).stream().findFirst();
    }

    @Override
    public void saveConnection(McpConnection value) {
        jdbc.update("""
                INSERT INTO platform_mcp_connections(
                    id, installation_id, tenant_id, managed_by, endpoint_url,
                    encrypted_auth_json, auth_type, state, external_account_id,
                    external_account_name, revision, created_at, updated_at, revoked_at)
                VALUES(CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    managed_by = EXCLUDED.managed_by,
                    endpoint_url = EXCLUDED.endpoint_url,
                    encrypted_auth_json = EXCLUDED.encrypted_auth_json,
                    auth_type = EXCLUDED.auth_type,
                    state = EXCLUDED.state,
                    external_account_id = EXCLUDED.external_account_id,
                    external_account_name = EXCLUDED.external_account_name,
                    revision = EXCLUDED.revision,
                    updated_at = EXCLUDED.updated_at,
                    revoked_at = EXCLUDED.revoked_at,
                    current_capability_snapshot_id = NULL,
                    last_qualified_at = NULL,
                    last_health_at = NULL,
                    last_error_code = NULL,
                    consecutive_failures = 0
                """, value.id(), value.installationId(), value.tenantId(), value.managedBy(),
                value.endpointUrl(), value.encryptedAuthJson(), value.authType().name(),
                value.state().name(), value.externalAccountId(), value.externalAccountName(),
                value.revision(), Timestamp.from(value.createdAt()),
                Timestamp.from(value.updatedAt()), timestamp(value.revokedAt()));
    }

    @Override
    public boolean saveConnectionIfRevision(McpConnection value, long expectedRevision) {
        return jdbc.update("""
                UPDATE platform_mcp_connections
                   SET managed_by = ?, endpoint_url = ?, encrypted_auth_json = ?, auth_type = ?,
                       state = ?, external_account_id = ?, external_account_name = ?, revision = ?,
                       updated_at = ?, revoked_at = ?,
                       current_capability_snapshot_id = NULL,
                       last_qualified_at = NULL,
                       last_health_at = NULL,
                       last_error_code = NULL,
                       consecutive_failures = 0
                 WHERE id = CAST(? AS UUID) AND revision = ?
                """, value.managedBy(), value.endpointUrl(), value.encryptedAuthJson(),
                value.authType().name(), value.state().name(), value.externalAccountId(),
                value.externalAccountName(), value.revision(), Timestamp.from(value.updatedAt()),
                timestamp(value.revokedAt()), value.id(), expectedRevision) == 1;
    }

    @Override
    public boolean refreshConnectionAuthorizationIfUnchanged(
            String connectionId,
            long expectedRevision,
            String expectedEncryptedAuthorization,
            String refreshedEncryptedAuthorization,
            Instant updatedAt) {
        return jdbc.update("""
                UPDATE platform_mcp_connections
                   SET encrypted_auth_json = ?, updated_at = ?
                 WHERE id = CAST(? AS UUID) AND revision = ?
                   AND encrypted_auth_json = ? AND state <> 'REVOKED'
                """, refreshedEncryptedAuthorization, Timestamp.from(updatedAt), connectionId,
                expectedRevision, expectedEncryptedAuthorization) == 1;
    }

    private McpMarketplaceEntry entry(ResultSet result, int row) throws SQLException {
        return new McpMarketplaceEntry(
                result.getString("id"), result.getString("slug"), result.getString("name"),
                result.getString("description"),
                McpTransportType.valueOf(result.getString("transport")),
                McpAuthType.valueOf(result.getString("auth_type")),
                result.getString("default_endpoint"), result.getString("manifest_json"),
                result.getBoolean("enabled"), result.getString("publisher_namespace"),
                result.getString("registry_name"),
                McpCatalogSourceType.valueOf(result.getString("source_type")),
                McpMarketplaceTrustTier.valueOf(result.getString("trust_tier")),
                McpMarketplaceLifecycle.valueOf(result.getString("lifecycle_state")),
                result.getString("current_version_id"), result.getString("current_version"),
                result.getString("current_manifest_sha256"), result.getLong("revision"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private McpServerVersion version(ResultSet result, int row) throws SQLException {
        return new McpServerVersion(
                result.getString("id"), result.getString("entry_id"),
                result.getString("version"),
                McpCatalogSourceType.valueOf(result.getString("source_type")),
                result.getString("source_uri"), result.getString("manifest_schema_uri"),
                result.getString("manifest_json"), result.getString("manifest_sha256"),
                McpAuthType.valueOf(result.getString("auth_type")),
                McpServerVersionState.valueOf(result.getString("lifecycle_state")),
                instant(result, "published_at"), result.getTimestamp("created_at").toInstant());
    }

    private McpServerTransport transport(ResultSet result, int row) throws SQLException {
        return new McpServerTransport(
                result.getString("id"), result.getString("server_version_id"),
                result.getInt("position"),
                McpTransportType.valueOf(result.getString("transport_type")),
                result.getString("endpoint_template"),
                result.getBoolean("endpoint_configurable"),
                result.getString("variables_schema_json"),
                result.getString("headers_schema_json"), result.getBoolean("enabled"),
                result.getTimestamp("created_at").toInstant());
    }

    private McpInstallation installation(ResultSet result, int row) throws SQLException {
        return new McpInstallation(
                result.getString("id"), result.getString("entry_id"),
                result.getString("server_version_id"), result.getString("server_version"),
                result.getString("tenant_id"), result.getString("subject_id"),
                result.getString("created_by"),
                McpInstallationScope.valueOf(result.getString("scope")),
                result.getString("display_name"),
                McpInstallationState.valueOf(result.getString("state")),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private McpConnection connection(ResultSet result, int row) throws SQLException {
        return new McpConnection(
                result.getString("id"), result.getString("installation_id"),
                result.getString("tenant_id"), result.getString("managed_by"),
                result.getString("endpoint_url"), result.getString("encrypted_auth_json"),
                McpAuthType.valueOf(result.getString("auth_type")),
                McpConnectionState.valueOf(result.getString("state")),
                result.getString("external_account_id"),
                result.getString("external_account_name"), result.getLong("revision"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant(),
                instant(result, "revoked_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
