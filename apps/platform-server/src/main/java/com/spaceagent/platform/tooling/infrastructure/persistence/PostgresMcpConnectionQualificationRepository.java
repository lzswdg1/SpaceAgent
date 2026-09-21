package com.spaceagent.platform.tooling.infrastructure.persistence;

import com.spaceagent.platform.tooling.domain.McpCapabilitySnapshot;
import com.spaceagent.platform.tooling.domain.McpConnectionObservation;
import com.spaceagent.platform.tooling.domain.McpConnectionObservationOutcome;
import com.spaceagent.platform.tooling.domain.McpConnectionQualificationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresMcpConnectionQualificationRepository
        implements McpConnectionQualificationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresMcpConnectionQualificationRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<McpCapabilitySnapshot> findCurrentSnapshot(String connectionId) {
        return jdbc.query("""
                SELECT snapshot.*
                  FROM platform_mcp_connections connection
                  JOIN platform_mcp_capability_snapshots snapshot
                    ON snapshot.id = connection.current_capability_snapshot_id
                   AND snapshot.connection_id = connection.id
                   AND snapshot.connection_revision = connection.revision
                 WHERE connection.id = CAST(? AS UUID)
                """, this::snapshot, connectionId).stream().findFirst();
    }

    @Override
    public List<McpConnectionObservation> findObservations(String connectionId, int limit) {
        return jdbc.query("""
                SELECT * FROM platform_mcp_connection_observations
                 WHERE connection_id = CAST(? AS UUID)
                 ORDER BY observation_sequence DESC LIMIT ?
                """, this::observation, connectionId, Math.max(1, Math.min(limit, 100)));
    }

    @Override
    public void completeSuccess(
            String connectionId,
            long expectedRevision,
            McpCapabilitySnapshot snapshot,
            McpConnectionObservation observation,
            Instant at) {
        transactions.executeWithoutResult(ignored -> completeSuccessInTransaction(
                connectionId, expectedRevision, snapshot, observation, at));
    }

    private void completeSuccessInTransaction(
            String connectionId,
            long expectedRevision,
            McpCapabilitySnapshot snapshot,
            McpConnectionObservation observation,
            Instant at) {
        jdbc.update("""
                INSERT INTO platform_mcp_capability_snapshots(
                    id, connection_id, connection_revision, protocol_version, server_name,
                    server_title, server_version, server_description, capabilities_json,
                    tools_json, snapshot_sha256, observed_at)
                VALUES(CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, CAST(? AS JSONB),
                    CAST(? AS JSONB), ?, ?)
                """, snapshot.id(), connectionId, snapshot.connectionRevision(),
                snapshot.protocolVersion(), snapshot.serverName(), snapshot.serverTitle(),
                snapshot.serverVersion(), snapshot.serverDescription(), snapshot.capabilitiesJson(),
                snapshot.toolsJson(), snapshot.snapshotSha256(), Timestamp.from(snapshot.observedAt()));
        int updated = jdbc.update("""
                UPDATE platform_mcp_connections
                   SET state = 'ACTIVE', current_capability_snapshot_id = CAST(? AS UUID),
                       last_qualified_at = ?, last_health_at = ?, last_error_code = NULL,
                       consecutive_failures = 0, updated_at = ?
                 WHERE id = CAST(? AS UUID) AND revision = ?
                   AND state IN ('PENDING_VALIDATION', 'ACTIVE', 'DEGRADED', 'ERROR')
                """, snapshot.id(), Timestamp.from(at), Timestamp.from(at), Timestamp.from(at),
                connectionId, expectedRevision);
        if (updated != 1) throw stale();
        insertObservation(observation);
    }

    @Override
    public void completeFailure(
            String connectionId,
            long expectedRevision,
            McpConnectionObservation observation,
            Instant at) {
        transactions.executeWithoutResult(ignored -> completeFailureInTransaction(
                connectionId, expectedRevision, observation, at));
    }

    private void completeFailureInTransaction(
            String connectionId,
            long expectedRevision,
            McpConnectionObservation observation,
            Instant at) {
        int updated = jdbc.update("""
                UPDATE platform_mcp_connections
                   SET state = CASE WHEN state IN ('ACTIVE', 'DEGRADED')
                                    THEN 'DEGRADED' ELSE 'ERROR' END,
                       last_health_at = ?, last_error_code = ?,
                       consecutive_failures = consecutive_failures + 1, updated_at = ?
                 WHERE id = CAST(? AS UUID) AND revision = ?
                   AND state IN ('PENDING_VALIDATION', 'ACTIVE', 'DEGRADED', 'ERROR')
                """, Timestamp.from(at), observation.safeErrorCode(), Timestamp.from(at),
                connectionId, expectedRevision);
        if (updated != 1) throw stale();
        insertObservation(observation);
    }

    private void insertObservation(McpConnectionObservation value) {
        jdbc.update("""
                INSERT INTO platform_mcp_connection_observations(
                    id, connection_id, connection_revision, outcome, latency_ms,
                    protocol_version, capability_snapshot_id, safe_error_code, observed_at)
                VALUES(CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, CAST(? AS UUID), ?, ?)
                """, value.id(), value.connectionId(), value.connectionRevision(),
                value.outcome().name(), value.latencyMs(), value.protocolVersion(),
                value.capabilitySnapshotId(), value.safeErrorCode(),
                Timestamp.from(value.observedAt()));
    }

    private McpCapabilitySnapshot snapshot(ResultSet result, int row) throws SQLException {
        return new McpCapabilitySnapshot(
                result.getString("id"), result.getString("connection_id"),
                result.getLong("connection_revision"), result.getString("protocol_version"),
                result.getString("server_name"), result.getString("server_title"),
                result.getString("server_version"), result.getString("server_description"),
                result.getString("capabilities_json"), result.getString("tools_json"),
                result.getString("snapshot_sha256"),
                result.getTimestamp("observed_at").toInstant());
    }

    private McpConnectionObservation observation(ResultSet result, int row) throws SQLException {
        return new McpConnectionObservation(
                result.getString("id"), result.getString("connection_id"),
                result.getLong("connection_revision"),
                McpConnectionObservationOutcome.valueOf(result.getString("outcome")),
                result.getLong("latency_ms"), result.getString("protocol_version"),
                result.getString("capability_snapshot_id"), result.getString("safe_error_code"),
                result.getTimestamp("observed_at").toInstant());
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("MCP connection changed while qualification was running");
    }
}
