package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.tooling.api.McpConnectionQualificationApplicationApi;
import com.spaceagent.platform.tooling.domain.McpCapabilitySnapshot;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionObservation;
import com.spaceagent.platform.tooling.domain.McpConnectionObservationOutcome;
import com.spaceagent.platform.tooling.domain.McpConnectionProbeGateway;
import com.spaceagent.platform.tooling.domain.McpConnectionQualificationRepository;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallation;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpInstallationState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpServerVersionState;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class McpConnectionQualificationService
        implements McpConnectionQualificationApplicationApi {
    private static final String QUALIFICATION_FAILED = "MCP_CONNECTION_QUALIFICATION_FAILED";

    private final McpMarketplaceRepository marketplace;
    private final McpConnectionQualificationRepository qualifications;
    private final McpConnectionAuthorizationService authorization;
    private final McpConnectionProbeGateway probe;
    private final IdentityApplicationApi identity;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final TimeProvider time;

    public McpConnectionQualificationService(
            McpMarketplaceRepository marketplace,
            McpConnectionQualificationRepository qualifications,
            McpConnectionAuthorizationService authorization,
            McpConnectionProbeGateway probe,
            IdentityApplicationApi identity,
            ObjectMapper json,
            IdGenerator ids,
            TimeProvider time) {
        this.marketplace = marketplace;
        this.qualifications = qualifications;
        this.authorization = authorization;
        this.probe = probe;
        this.identity = identity;
        this.json = json;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public QualificationView qualify(QualifyCommand command) {
        McpConnection configured = requireConnection(
                command.tenantId(), command.userId(), command.connectionId(), true);
        if (configured.state() == McpConnectionState.PENDING_AUTH) {
            throw conflict("MCP connection authorization must complete before qualification");
        }
        long started = System.nanoTime();
        McpConnection connection = configured;
        McpConnectionProbeGateway.ProbeResult result;
        try {
            McpConnectionAuthorizationService.AuthorizedConnection authorized =
                    authorization.authorize(configured);
            connection = authorized.connection();
            result = probe.probe(connection, authorized.authorization());
        } catch (RuntimeException error) {
            completeFailure(connection, latency(started));
            throw new BusinessException(
                    "MCP connection qualification failed", HttpStatus.BAD_GATEWAY,
                    QUALIFICATION_FAILED);
        }
        try {
            return completeSuccess(connection, result, latency(started));
        } catch (BusinessException error) {
            if ("MCP_CONNECTION_CHANGED".equals(error.getCode())) throw error;
            completeFailure(connection, latency(started));
            throw error;
        }
    }

    @Override
    public QualificationView qualification(
            String tenantId, String userId, String connectionId) {
        McpConnection connection = requireConnection(tenantId, userId, connectionId, false);
        McpCapabilitySnapshot snapshot = qualifications.findCurrentSnapshot(connection.id())
                .orElse(null);
        McpConnectionObservation last = qualifications.findObservations(connection.id(), 1)
                .stream().findFirst().orElse(null);
        return view(connection, snapshot, last);
    }

    @Override
    public List<ObservationView> observations(
            String tenantId, String userId, String connectionId, int limit) {
        McpConnection connection = requireConnection(tenantId, userId, connectionId, false);
        return qualifications.findObservations(connection.id(), Math.max(1, Math.min(limit, 100)))
                .stream().map(McpConnectionQualificationService::view).toList();
    }

    private QualificationView completeSuccess(
            McpConnection connection,
            McpConnectionProbeGateway.ProbeResult result,
            long latencyMs) {
        Instant now = time.now();
        String protocol = protocol(result.protocolVersion());
        String serverName = required(result.serverName(), "serverName", 200);
        String serverVersion = required(result.serverVersion(), "serverVersion", 100);
        String title = optional(result.serverTitle(), 200);
        String description = optional(result.serverDescription(), 1_000);
        if (result.tools().size() > 100) throw invalidProbe();
        String capabilitiesJson = write(result.capabilities(), 500_000);
        String toolsJson = write(result.tools(), 1_000_000);
        String digest = sha256(protocol + "\n" + serverName + "\n" + serverVersion
                + "\n" + capabilitiesJson + "\n" + toolsJson);
        McpCapabilitySnapshot snapshot = new McpCapabilitySnapshot(
                ids.nextId(), connection.id(), connection.revision(), protocol, serverName,
                title, serverVersion, description, capabilitiesJson, toolsJson, digest, now);
        McpConnectionObservation observation = new McpConnectionObservation(
                ids.nextId(), connection.id(), connection.revision(),
                McpConnectionObservationOutcome.SUCCEEDED, latencyMs, protocol,
                snapshot.id(), null, now);
        try {
            qualifications.completeSuccess(
                    connection.id(), connection.revision(), snapshot, observation, now);
        } catch (RuntimeException stale) {
            throw changed();
        }
        McpConnection active = marketplace.findConnection(connection.id())
                .orElseThrow(McpConnectionQualificationService::changed);
        return view(active, snapshot, observation);
    }

    private void completeFailure(McpConnection connection, long latencyMs) {
        Instant now = time.now();
        McpConnectionObservation observation = new McpConnectionObservation(
                ids.nextId(), connection.id(), connection.revision(),
                McpConnectionObservationOutcome.FAILED, latencyMs, null, null,
                QUALIFICATION_FAILED, now);
        try {
            qualifications.completeFailure(
                    connection.id(), connection.revision(), observation, now);
        } catch (RuntimeException stale) {
            throw changed();
        }
    }

    private McpConnection requireConnection(
            String tenantId, String userId, String connectionId, boolean write) {
        TenantMembershipView membership = identity.findTenantMembership(tenantId, userId)
                .filter(value -> value.status() == TenantMembershipStatus.ACTIVE)
                .orElseThrow(() -> denied("Organization membership required"));
        McpConnection connection = marketplace.findConnection(connectionId)
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.state() != McpConnectionState.REVOKED)
                .orElseThrow(McpConnectionQualificationService::notFound);
        McpInstallation installation = marketplace.findInstallation(connection.installationId())
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.state() == McpInstallationState.INSTALLED)
                .orElseThrow(McpConnectionQualificationService::notFound);
        if (installation.scope() == McpInstallationScope.USER
                && !installation.subjectId().equals(userId)) {
            throw notFound();
        }
        if (write && installation.scope() == McpInstallationScope.ORGANIZATION
                && membership.role() != TenantRole.OWNER
                && membership.role() != TenantRole.ADMIN) {
            throw denied("Organization manager required");
        }
        marketplace.findVersion(installation.serverVersionId())
                .filter(version -> version.entryId().equals(installation.entryId()))
                .filter(version -> version.lifecycleState() != McpServerVersionState.REVOKED)
                .orElseThrow(McpConnectionQualificationService::notFound);
        return connection;
    }

    private QualificationView view(
            McpConnection connection,
            McpCapabilitySnapshot snapshot,
            McpConnectionObservation last) {
        return new QualificationView(
                connection.id(), connection.state(), connection.revision(),
                snapshot == null ? null : snapshot.id(),
                snapshot == null ? null : snapshot.snapshotSha256(),
                snapshot == null ? null : snapshot.protocolVersion(),
                snapshot == null ? null : snapshot.serverName(),
                snapshot == null ? null : snapshot.serverTitle(),
                snapshot == null ? null : snapshot.serverVersion(),
                snapshot == null ? 0 : toolCount(snapshot.toolsJson()),
                snapshot == null ? null : snapshot.observedAt(),
                last == null ? null : view(last));
    }

    private int toolCount(String toolsJson) {
        try {
            JsonNode node = json.readTree(toolsJson);
            return node.isArray() ? node.size() : 0;
        } catch (Exception error) {
            throw new IllegalStateException("MCP capability snapshot is invalid", error);
        }
    }

    private static ObservationView view(McpConnectionObservation value) {
        return new ObservationView(
                value.id(), value.outcome(), value.connectionRevision(), value.latencyMs(),
                value.protocolVersion(), value.capabilitySnapshotId(), value.safeErrorCode(),
                value.observedAt());
    }

    private String write(Object value, int maximum) {
        try {
            String result = json.writeValueAsString(value);
            if (result.length() > maximum) throw invalidProbe();
            return result;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw invalidProbe();
        }
    }

    private static String protocol(String value) {
        String protocol = required(value, "protocolVersion", 40);
        if (!protocol.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalidProbe();
        return protocol;
    }

    private static String required(String value, String field, int maximum) {
        String result = optional(value, maximum);
        if (result == null) throw invalidProbe();
        return result;
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > maximum || result.contains("\r") || result.contains("\n")) {
            throw invalidProbe();
        }
        return result;
    }

    private static long latency(long started) {
        return Math.min(120_000, Math.max(0, (System.nanoTime() - started) / 1_000_000));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash MCP capability snapshot", error);
        }
    }

    private static BusinessException invalidProbe() {
        return new BusinessException(
                "MCP server returned invalid qualification metadata", HttpStatus.BAD_GATEWAY,
                QUALIFICATION_FAILED);
    }

    private static BusinessException changed() {
        return new BusinessException(
                "MCP connection changed while qualification was running", HttpStatus.CONFLICT,
                "MCP_CONNECTION_CHANGED");
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(message, HttpStatus.CONFLICT, "MCP_STATE_CONFLICT");
    }

    private static BusinessException denied(String message) {
        return new BusinessException(message, HttpStatus.FORBIDDEN, "MCP_ACCESS_DENIED");
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "MCP connection not found", HttpStatus.NOT_FOUND, "MCP_NOT_FOUND");
    }
}
