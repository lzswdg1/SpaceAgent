package com.spaceagent.platform.tooling.infrastructure.memory;

import com.spaceagent.platform.tooling.domain.McpCapabilitySnapshot;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionObservation;
import com.spaceagent.platform.tooling.domain.McpConnectionQualificationRepository;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryMcpConnectionQualificationRepository
        implements McpConnectionQualificationRepository {
    private final McpMarketplaceRepository marketplace;
    private final Map<String, McpCapabilitySnapshot> current = new ConcurrentHashMap<>();
    private final Map<String, List<McpConnectionObservation>> observations =
            new ConcurrentHashMap<>();

    public InMemoryMcpConnectionQualificationRepository(McpMarketplaceRepository marketplace) {
        this.marketplace = marketplace;
    }

    @Override
    public Optional<McpCapabilitySnapshot> findCurrentSnapshot(String connectionId) {
        McpCapabilitySnapshot snapshot = current.get(connectionId);
        return marketplace.findConnection(connectionId)
                .filter(connection -> snapshot != null
                        && connection.revision() == snapshot.connectionRevision())
                .map(ignored -> snapshot);
    }

    @Override
    public List<McpConnectionObservation> findObservations(String connectionId, int limit) {
        List<McpConnectionObservation> stored =
                observations.getOrDefault(connectionId, List.of());
        int count = Math.min(Math.max(1, Math.min(limit, 100)), stored.size());
        List<McpConnectionObservation> newestFirst = new ArrayList<>(count);
        for (int index = stored.size() - 1; index >= stored.size() - count; index--) {
            newestFirst.add(stored.get(index));
        }
        return List.copyOf(newestFirst);
    }

    @Override
    public synchronized void completeSuccess(
            String connectionId,
            long expectedRevision,
            McpCapabilitySnapshot snapshot,
            McpConnectionObservation observation,
            Instant at) {
        McpConnection connection = eligible(connectionId, expectedRevision);
        McpConnection active = state(connection, McpConnectionState.ACTIVE, at);
        if (!marketplace.saveConnectionIfRevision(active, expectedRevision)) throw stale();
        current.put(connectionId, snapshot);
        observations.computeIfAbsent(connectionId, ignored -> new ArrayList<>()).add(observation);
    }

    @Override
    public synchronized void completeFailure(
            String connectionId,
            long expectedRevision,
            McpConnectionObservation observation,
            Instant at) {
        McpConnection connection = eligible(connectionId, expectedRevision);
        McpConnectionState next = connection.state() == McpConnectionState.ACTIVE
                || connection.state() == McpConnectionState.DEGRADED
                ? McpConnectionState.DEGRADED : McpConnectionState.ERROR;
        if (!marketplace.saveConnectionIfRevision(state(connection, next, at), expectedRevision)) {
            throw stale();
        }
        observations.computeIfAbsent(connectionId, ignored -> new ArrayList<>()).add(observation);
    }

    private McpConnection eligible(String connectionId, long expectedRevision) {
        McpConnection connection = marketplace.findConnection(connectionId).orElseThrow(this::stale);
        if (connection.revision() != expectedRevision
                || connection.state() == McpConnectionState.PENDING_AUTH
                || connection.state() == McpConnectionState.REVOKED) {
            throw stale();
        }
        return connection;
    }

    private static McpConnection state(
            McpConnection connection, McpConnectionState state, Instant at) {
        return new McpConnection(
                connection.id(), connection.installationId(), connection.tenantId(),
                connection.managedBy(), connection.endpointUrl(), connection.encryptedAuthJson(),
                connection.authType(), state, connection.externalAccountId(),
                connection.externalAccountName(), connection.revision(), connection.createdAt(),
                at, connection.revokedAt());
    }

    private IllegalStateException stale() {
        return new IllegalStateException("MCP connection changed while qualification was running");
    }
}
