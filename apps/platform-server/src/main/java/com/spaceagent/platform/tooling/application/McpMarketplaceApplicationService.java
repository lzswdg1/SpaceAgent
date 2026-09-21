package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallation;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpInstallationState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceEntry;
import com.spaceagent.platform.tooling.domain.McpMarketplaceLifecycle;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpMarketplaceTrustTier;
import com.spaceagent.platform.tooling.domain.McpServerTransport;
import com.spaceagent.platform.tooling.domain.McpServerVersion;
import com.spaceagent.platform.tooling.domain.McpServerVersionState;
import com.spaceagent.platform.tooling.domain.McpTransportType;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class McpMarketplaceApplicationService implements McpMarketplaceApplicationApi {
    private final McpMarketplaceRepository repository;
    private final IdentityApplicationApi identity;
    private final McpConnectionSecretCipher cipher;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final TimeProvider time;

    public McpMarketplaceApplicationService(
            McpMarketplaceRepository repository,
            IdentityApplicationApi identity,
            McpConnectionSecretCipher cipher,
            ObjectMapper json,
            IdGenerator ids,
            TimeProvider time) {
        this.repository = repository;
        this.identity = identity;
        this.cipher = cipher;
        this.json = json;
        this.ids = ids;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EntryView> catalog() {
        return repository.findEntries().stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServerVersionView> versions(String entryId) {
        McpMarketplaceEntry entry = requireEntry(entryId);
        return repository.findVersions(entry.id()).stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ServerVersionView version(String entryId, String versionId) {
        requireEntry(entryId);
        McpServerVersion version = repository.findVersion(versionId)
                .filter(value -> value.entryId().equals(entryId))
                .orElseThrow(() -> notFound("MCP server version"));
        return view(version);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InstallationView> installations(String tenantId, String userId) {
        membership(tenantId, userId);
        return repository.findInstallations(tenantId, userId).stream()
                .map(value -> view(value)).toList();
    }

    @Override
    public InstallationView install(InstallCommand command) {
        TenantMembershipView membership = membership(command.tenantId(), command.userId());
        McpMarketplaceEntry entry = requireEntry(command.entryId());
        requireInstallable(entry);
        if (command.scope() == null) throw invalid("MCP installation scope is required");
        String subject = command.scope() == McpInstallationScope.ORGANIZATION
                ? command.tenantId() : command.userId();
        if (command.scope() == McpInstallationScope.ORGANIZATION) admin(membership);

        McpInstallation current = repository.findInstallation(
                command.tenantId(), entry.id(), command.scope(), subject).orElse(null);
        Instant now = time.now();
        if (current != null) {
            if (command.serverVersionId() != null
                    && !command.serverVersionId().equals(current.serverVersionId())) {
                throw conflict("MCP installation version upgrades require an explicit workflow");
            }
            McpInstallation updated = new McpInstallation(
                    current.id(), current.entryId(), current.serverVersionId(),
                    current.serverVersion(), current.tenantId(), current.subjectId(),
                    current.createdBy(), current.scope(), name(command.displayName(), entry.name()),
                    McpInstallationState.INSTALLED, current.createdAt(), now);
            repository.saveInstallation(updated);
            return view(updated);
        }

        McpServerVersion version = installableVersion(entry, command.serverVersionId());
        McpInstallation created = new McpInstallation(
                ids.nextId(), entry.id(), version.id(), version.version(), command.tenantId(),
                subject, command.userId(), command.scope(),
                name(command.displayName(), entry.name()), McpInstallationState.INSTALLED,
                now, now);
        repository.saveInstallation(created);
        return view(created);
    }

    @Override
    public InstallationView disable(MutateCommand command) {
        McpInstallation current = requireInstallation(
                command.tenantId(), command.userId(), command.id(), true);
        McpInstallation disabled = new McpInstallation(
                current.id(), current.entryId(), current.serverVersionId(),
                current.serverVersion(), current.tenantId(), current.subjectId(),
                current.createdBy(), current.scope(), current.displayName(),
                McpInstallationState.DISABLED, current.createdAt(), time.now());
        repository.saveInstallation(disabled);
        return view(disabled);
    }

    @Override
    public ConnectionView connect(ConnectCommand command) {
        McpInstallation installation = requireInstallation(
                command.tenantId(), command.userId(), command.installationId(), true);
        if (installation.state() != McpInstallationState.INSTALLED) {
            throw conflict("MCP installation is disabled");
        }
        McpMarketplaceEntry entry = requireEntry(installation.entryId());
        McpServerVersion version = repository.findVersion(installation.serverVersionId())
                .filter(value -> value.entryId().equals(entry.id()))
                .filter(value -> value.lifecycleState() != McpServerVersionState.REVOKED)
                .orElseThrow(() -> conflict("Pinned MCP server version is unavailable"));
        McpServerTransport transport = primaryTransport(version.id());
        McpAuthType authType = command.authType() == null
                ? version.authType() : command.authType();
        String endpoint = endpoint(command.endpointUrl() == null
                ? transport.endpointTemplate() : command.endpointUrl());
        Map<String, String> secret = command.auth() == null ? Map.of() : Map.copyOf(command.auth());
        if (entry.slug().equals("github")
                && GithubMcpProfiles.isOfficialRemote(endpoint)
                && (authType != McpAuthType.OAUTH2 || !secret.isEmpty())) {
            throw invalid("Official GitHub MCP requires host OAuth; direct tokens are not accepted");
        }
        if (secret.size() > 16) throw invalid("MCP auth has too many fields");
        String plaintext;
        try {
            plaintext = json.writeValueAsString(secret);
        } catch (Exception error) {
            throw invalid("MCP auth is invalid");
        }
        if (plaintext.length() > 16_000) throw invalid("MCP auth is too large");

        Instant now = time.now();
        McpConnection old = repository.findConnectionByInstallation(installation.id()).orElse(null);
        McpConnectionState state = authType == McpAuthType.OAUTH2 && secret.isEmpty()
                ? McpConnectionState.PENDING_AUTH : McpConnectionState.PENDING_VALIDATION;
        McpConnection connection = new McpConnection(
                old == null ? ids.nextId() : old.id(), installation.id(), installation.tenantId(),
                command.userId(), endpoint, cipher.encrypt(plaintext), authType, state,
                null, null, old == null ? 1 : old.revision() + 1,
                old == null ? now : old.createdAt(), now, null);
        repository.saveConnection(connection);
        return view(connection);
    }

    @Override
    public ConnectionView revokeConnection(MutateCommand command) {
        McpInstallation installation = requireInstallationByConnection(
                command.tenantId(), command.userId(), command.id());
        McpConnection old = repository.findConnectionByInstallation(installation.id())
                .orElseThrow(() -> notFound("MCP connection"));
        Instant now = time.now();
        McpConnection revoked = new McpConnection(
                old.id(), old.installationId(), old.tenantId(), command.userId(),
                old.endpointUrl(), old.encryptedAuthJson(), old.authType(),
                McpConnectionState.REVOKED, old.externalAccountId(), old.externalAccountName(),
                old.revision() + 1, old.createdAt(), now, now);
        repository.saveConnection(revoked);
        return view(revoked);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectionView> connections(String tenantId, String userId) {
        membership(tenantId, userId);
        return repository.findConnections(tenantId, userId).stream()
                .map(value -> view(value)).toList();
    }

    private McpServerVersion installableVersion(
            McpMarketplaceEntry entry, String requestedVersionId) {
        String versionId = requestedVersionId == null || requestedVersionId.isBlank()
                ? entry.currentVersionId() : requestedVersionId.trim();
        return repository.findVersion(versionId)
                .filter(value -> value.entryId().equals(entry.id()))
                .filter(value -> value.lifecycleState() == McpServerVersionState.APPROVED)
                .orElseThrow(() -> conflict("MCP server version is not approved for installation"));
    }

    private McpServerTransport primaryTransport(String serverVersionId) {
        return repository.findTransports(serverVersionId).stream()
                .filter(McpServerTransport::enabled)
                .filter(value -> value.transportType() == McpTransportType.STREAMABLE_HTTP)
                .min(Comparator.comparingInt(McpServerTransport::position))
                .orElseThrow(() -> conflict("MCP server version has no supported remote transport"));
    }

    private McpInstallation requireInstallationByConnection(
            String tenantId, String userId, String connectionId) {
        McpConnection connection = repository.findConnections(tenantId, userId).stream()
                .filter(value -> value.id().equals(connectionId))
                .findFirst().orElseThrow(() -> notFound("MCP connection"));
        return requireInstallation(tenantId, userId, connection.installationId(), true);
    }

    private McpInstallation requireInstallation(
            String tenantId, String userId, String installationId, boolean write) {
        TenantMembershipView membership = membership(tenantId, userId);
        McpInstallation installation = repository.findInstallation(installationId)
                .filter(value -> value.tenantId().equals(tenantId))
                .orElseThrow(() -> notFound("MCP installation"));
        if (installation.scope() == McpInstallationScope.USER
                && !installation.subjectId().equals(userId)) {
            throw notFound("MCP installation");
        }
        if (write && installation.scope() == McpInstallationScope.ORGANIZATION) {
            admin(membership);
        }
        return installation;
    }

    private McpMarketplaceEntry requireEntry(String id) {
        return repository.findEntry(id).orElseThrow(() -> notFound("MCP marketplace entry"));
    }

    private TenantMembershipView membership(String tenantId, String userId) {
        return identity.findTenantMembership(tenantId, userId)
                .filter(value -> value.status() == TenantMembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Organization membership required", HttpStatus.FORBIDDEN,
                        "ORGANIZATION_MEMBERSHIP_REQUIRED"));
    }

    private static void requireInstallable(McpMarketplaceEntry entry) {
        if (entry.lifecycle() != McpMarketplaceLifecycle.ACTIVE
                || (entry.trustTier() != McpMarketplaceTrustTier.PLATFORM_CURATED
                && entry.trustTier() != McpMarketplaceTrustTier.REGISTRY_VERIFIED)) {
            throw conflict("MCP marketplace entry is not installable");
        }
    }

    private static void admin(TenantMembershipView membership) {
        if (membership.role() != TenantRole.OWNER && membership.role() != TenantRole.ADMIN) {
            throw new BusinessException(
                    "Organization manager required", HttpStatus.FORBIDDEN, "MCP_ACCESS_DENIED");
        }
    }

    private static String endpoint(String value) {
        if (value == null || value.isBlank()) throw invalid("MCP endpoint is required");
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return uri.normalize().toString();
        } catch (Exception error) {
            throw invalid("MCP endpoint must be an HTTPS URL");
        }
    }

    private static String name(String value, String fallback) {
        String name = value == null || value.isBlank() ? fallback : value.trim();
        if (name.length() > 120) throw invalid("MCP display name is too long");
        return name;
    }

    private EntryView view(McpMarketplaceEntry value) {
        return new EntryView(
                value.id(), value.slug(), value.name(), value.description(), value.transport(),
                value.authType(), value.defaultEndpoint(), value.manifestJson(),
                value.publisherNamespace(), value.registryName(), value.sourceType(),
                value.trustTier(), value.lifecycle(), value.currentVersionId(),
                value.currentVersion(), value.currentManifestSha256(), value.revision());
    }

    private ServerVersionView view(McpServerVersion value) {
        return new ServerVersionView(
                value.id(), value.entryId(), value.version(), value.sourceType(),
                value.sourceUri(), value.manifestSchemaUri(), value.manifestJson(),
                value.manifestSha256(), value.authType(), value.lifecycleState(),
                value.publishedAt(), value.createdAt(),
                repository.findTransports(value.id()).stream()
                        .map(transport -> view(transport)).toList());
    }

    private static TransportView view(McpServerTransport value) {
        return new TransportView(
                value.id(), value.position(), value.transportType(), value.endpointTemplate(),
                value.endpointConfigurable(), value.variablesSchemaJson(),
                value.headersSchemaJson(), value.enabled());
    }

    private static InstallationView view(McpInstallation value) {
        return new InstallationView(
                value.id(), value.entryId(), value.serverVersionId(), value.serverVersion(),
                value.tenantId(), value.subjectId(), value.createdBy(), value.scope(),
                value.displayName(), value.state(), value.createdAt(), value.updatedAt());
    }

    private static ConnectionView view(McpConnection value) {
        return new ConnectionView(
                value.id(), value.installationId(), value.tenantId(), value.managedBy(),
                value.endpointUrl(), value.authType(), value.state(),
                value.authType() != McpAuthType.NONE
                        && value.state() != McpConnectionState.PENDING_AUTH,
                value.externalAccountId(), value.externalAccountName(), value.revision(),
                value.createdAt(), value.updatedAt(), value.revokedAt());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "MCP_INPUT_INVALID");
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(message, HttpStatus.CONFLICT, "MCP_STATE_CONFLICT");
    }

    private static BusinessException notFound(String noun) {
        return new BusinessException(noun + " not found", HttpStatus.NOT_FOUND, "MCP_NOT_FOUND");
    }
}
