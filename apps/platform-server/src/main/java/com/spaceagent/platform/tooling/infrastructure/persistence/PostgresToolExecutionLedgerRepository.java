package com.spaceagent.platform.tooling.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecision;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecisionType;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionCompletionRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionLedger;
import com.spaceagent.platform.tooling.domain.ToolExecutionLedgerRepository;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationEvidence;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionResult;
import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionType;
import com.spaceagent.platform.tooling.domain.ToolExecutionUnknownRequest;
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
import java.util.UUID;

/**
 * PostgreSQL-owned atomic claim and fenced transition adapter.
 *
 * <p>Each mutating method owns one short transaction. No caller needs to keep a row lock
 * while invoking the external sandbox gateway.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresToolExecutionLedgerRepository implements ToolExecutionLedgerRepository {

    private static final String COLUMNS = """
            id, agent_run_id, run_step_id, tool_name, tool_call_id,
            idempotency_key, arguments, input_hash, status, result, result_ref, error,
            started_at, completed_at, claim_token, claim_owner, lease_until, revision,
            claimed_at, updated_at, reconciliation_evidence::text AS reconciliation_evidence,
            resolved_at, resolved_by, resolution_reason
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.spaceagent.platform.shared.api.ExecutionAttributionQuery attribution;

    public PostgresToolExecutionLedgerRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ToolExecutionLedger> findById(String id) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_tool_execution_ledger WHERE id = ?",
                this::map,
                id).stream().findFirst();
    }

    @Override
    public Optional<ToolExecutionLedger> findByRunIdAndToolCallId(String agentRunId, String toolCallId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_tool_execution_ledger "
                        + "WHERE agent_run_id = ? AND tool_call_id = ?",
                this::map,
                agentRunId,
                toolCallId).stream().findFirst();
    }

    @Override
    public List<ToolExecutionLedger> findByRunId(String agentRunId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_tool_execution_ledger "
                        + "WHERE agent_run_id = ? ORDER BY started_at, id",
                this::map,
                agentRunId);
    }

    @Override
    public ToolExecutionClaimDecision claim(ToolExecutionClaimRequest request) {
        return required(transactionTemplate.execute(ignored -> claimInTransaction(request)));
    }

    @Override
    public ToolExecutionTransitionResult complete(ToolExecutionCompletionRequest request) {
        return required(transactionTemplate.execute(ignored -> completeInTransaction(request)));
    }

    @Override
    public ToolExecutionTransitionResult markUnknown(ToolExecutionUnknownRequest request) {
        return required(transactionTemplate.execute(ignored -> markUnknownInTransaction(request)));
    }

    @Override
    public ToolExecutionTransitionResult reconcileUnknown(ToolExecutionReconciliationRequest request) {
        return required(transactionTemplate.execute(ignored -> reconcileInTransaction(request)));
    }

    private ToolExecutionClaimDecision claimInTransaction(ToolExecutionClaimRequest request) {
        var scope=attribution==null?null:attribution.resolve(request.agentRunId()).orElse(null);
        Optional<ToolExecutionLedger> inserted = jdbcTemplate.query("""
                WITH claim_time AS (SELECT clock_timestamp() AS now_value)
                INSERT INTO platform_tool_execution_ledger (
                    id, agent_run_id, run_step_id, tool_name, tool_call_id,
                    idempotency_key, arguments, input_hash, status, started_at,
                    claim_token, claim_owner, lease_until, revision, claimed_at, updated_at,usage_tenant_id,usage_actor_id
                )
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', now_value AT TIME ZONE 'UTC',
                       CAST(? AS UUID), ?, now_value + (? * INTERVAL '1 second'),
                       1, now_value, now_value,?,?
                FROM claim_time
                ON CONFLICT (agent_run_id, tool_call_id) DO NOTHING
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.id(),
                request.agentRunId(),
                request.runStepId(),
                request.toolName(),
                request.toolCallId(),
                request.idempotencyKey(),
                request.arguments(),
                request.inputHash(),
                request.claimToken(),
                request.claimOwner(),
                request.leaseSeconds(),scope==null?null:scope.tenantId(),scope==null?null:scope.actorId()).stream().findFirst();
        if (inserted.isPresent()) {
            return decision(ToolExecutionClaimDecisionType.CLAIMED, inserted.get(), null);
        }

        LockedLedgerRow locked = requireLocked(request.agentRunId(), request.toolCallId());
        ToolExecutionLedger existing = locked.ledger();
        if (!request.inputHash().equals(existing.inputHash())
                || idempotencyKeyConflicts(request.idempotencyKey(), existing.idempotencyKey())) {
            return new ToolExecutionClaimDecision(
                    ToolExecutionClaimDecisionType.CONFLICT,
                    existing,
                    existing.inputHash(),
                    request.inputHash(),
                    "logical tool call input does not match the persisted claim");
        }
        if (isTerminal(existing.status())) {
            return decision(ToolExecutionClaimDecisionType.REPLAY, existing, null);
        }
        if (existing.status() == ToolExecutionStatus.UNKNOWN) {
            return decision(ToolExecutionClaimDecisionType.UNKNOWN, existing, existing.error());
        }
        if (existing.status() == ToolExecutionStatus.RUNNING && locked.leaseActive()) {
            return decision(ToolExecutionClaimDecisionType.BUSY, existing, "tool claim lease is active");
        }

        ToolExecutionReconciliationEvidence evidence = new ToolExecutionReconciliationEvidence(
                "claim lease expired or legacy non-terminal state observed",
                null,
                java.util.Map.of("previousStatus", existing.status().name()));
        ToolExecutionLedger unknown = markExpiredOrLegacyUnknown(
                existing,
                "tool claim lease expired or legacy non-terminal state is ambiguous",
                evidence);
        return decision(ToolExecutionClaimDecisionType.UNKNOWN, unknown, unknown.error());
    }

    private ToolExecutionTransitionResult completeInTransaction(ToolExecutionCompletionRequest request) {
        Optional<ToolExecutionLedger> completed = jdbcTemplate.query("""
                UPDATE platform_tool_execution_ledger
                   SET status = ?,
                       result = ?,
                       result_ref = ?,
                       error = ?,
                       completed_at = clock_timestamp() AT TIME ZONE 'UTC',
                       revision = revision + 1,
                       updated_at = clock_timestamp()
                 WHERE agent_run_id = ?
                   AND tool_call_id = ?
                   AND status = 'RUNNING'
                   AND claim_token = CAST(? AS UUID)
                   AND revision = ?
                   AND lease_until > clock_timestamp()
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.status().name(),
                request.result(),
                request.resultRef(),
                request.error(),
                request.agentRunId(),
                request.toolCallId(),
                request.claimToken(),
                request.expectedRevision()).stream().findFirst();
        if (completed.isPresent()) {
            return transition(ToolExecutionTransitionType.APPLIED, completed.get());
        }

        LockedLedgerRow locked = requireLocked(request.agentRunId(), request.toolCallId());
        ToolExecutionLedger current = locked.ledger();
        if (isTerminal(current.status())) {
            return transition(ToolExecutionTransitionType.CURRENT_TERMINAL, current);
        }
        if (current.status() == ToolExecutionStatus.UNKNOWN) {
            return transition(ToolExecutionTransitionType.CURRENT_UNKNOWN, current);
        }
        if (current.status() == ToolExecutionStatus.RUNNING && !locked.leaseActive()) {
            ToolExecutionLedger unknown = markExpiredOrLegacyUnknown(
                    current,
                    "tool result arrived after the claim lease expired",
                    request.lateEvidence());
            return transition(ToolExecutionTransitionType.CURRENT_UNKNOWN, unknown);
        }
        if (current.status() == ToolExecutionStatus.PENDING) {
            ToolExecutionLedger unknown = markExpiredOrLegacyUnknown(
                    current,
                    "legacy PENDING tool execution cannot be completed safely",
                    request.lateEvidence());
            return transition(ToolExecutionTransitionType.CURRENT_UNKNOWN, unknown);
        }
        return transition(ToolExecutionTransitionType.CLAIM_LOST, current);
    }

    private ToolExecutionTransitionResult markUnknownInTransaction(ToolExecutionUnknownRequest request) {
        Optional<ToolExecutionLedger> updated = jdbcTemplate.query("""
                UPDATE platform_tool_execution_ledger
                   SET status = 'UNKNOWN',
                       claim_token = NULL,
                       error = ?,
                       revision = revision + 1,
                       updated_at = clock_timestamp(),
                       reconciliation_evidence = COALESCE(CAST(? AS JSONB), reconciliation_evidence)
                 WHERE agent_run_id = ?
                   AND tool_call_id = ?
                   AND status = 'RUNNING'
                   AND claim_token = CAST(? AS UUID)
                   AND revision = ?
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.reason(),
                toJson(request.evidence()),
                request.agentRunId(),
                request.toolCallId(),
                request.claimToken(),
                request.expectedRevision()).stream().findFirst();
        if (updated.isPresent()) {
            return transition(ToolExecutionTransitionType.APPLIED, updated.get());
        }

        ToolExecutionLedger current = requireLocked(request.agentRunId(), request.toolCallId()).ledger();
        if (isTerminal(current.status())) {
            return transition(ToolExecutionTransitionType.CURRENT_TERMINAL, current);
        }
        if (current.status() == ToolExecutionStatus.UNKNOWN) {
            return transition(ToolExecutionTransitionType.CURRENT_UNKNOWN, current);
        }
        return transition(ToolExecutionTransitionType.CLAIM_LOST, current);
    }

    private ToolExecutionTransitionResult reconcileInTransaction(ToolExecutionReconciliationRequest request) {
        Optional<ToolExecutionLedger> resolved = jdbcTemplate.query("""
                UPDATE platform_tool_execution_ledger
                   SET status = ?,
                       result = ?,
                       result_ref = ?,
                       error = ?,
                       completed_at = clock_timestamp() AT TIME ZONE 'UTC',
                       claim_token = NULL,
                       revision = revision + 1,
                       updated_at = clock_timestamp(),
                       reconciliation_evidence = CAST(? AS JSONB),
                       resolved_at = clock_timestamp(),
                       resolved_by = ?,
                       resolution_reason = ?
                 WHERE agent_run_id = ?
                   AND tool_call_id = ?
                   AND status = 'UNKNOWN'
                   AND input_hash = ?
                   AND revision = ?
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.resolution().name(),
                request.result(),
                request.resultRef(),
                request.error(),
                toJson(request.evidence()),
                request.resolvedBy(),
                request.resolutionReason(),
                request.agentRunId(),
                request.toolCallId(),
                request.inputHash(),
                request.expectedRevision()).stream().findFirst();
        if (resolved.isPresent()) {
            return transition(ToolExecutionTransitionType.APPLIED, resolved.get());
        }

        ToolExecutionLedger current = requireLocked(request.agentRunId(), request.toolCallId()).ledger();
        if (!request.inputHash().equals(current.inputHash())) {
            return transition(ToolExecutionTransitionType.CONFLICT, current);
        }
        if (isTerminal(current.status())) {
            return transition(ToolExecutionTransitionType.CURRENT_TERMINAL, current);
        }
        return transition(ToolExecutionTransitionType.CLAIM_LOST, current);
    }

    private ToolExecutionLedger markExpiredOrLegacyUnknown(
            ToolExecutionLedger current,
            String reason,
            ToolExecutionReconciliationEvidence evidence) {
        return jdbcTemplate.query("""
                UPDATE platform_tool_execution_ledger
                   SET status = 'UNKNOWN',
                       claim_token = NULL,
                       error = ?,
                       revision = revision + 1,
                       updated_at = clock_timestamp(),
                       reconciliation_evidence = COALESCE(CAST(? AS JSONB), reconciliation_evidence)
                 WHERE id = ?
                   AND revision = ?
                   AND (status = 'PENDING'
                        OR (status = 'RUNNING'
                            AND (lease_until IS NULL OR lease_until <= clock_timestamp())))
                RETURNING """ + " " + COLUMNS,
                this::map,
                reason,
                toJson(evidence),
                current.id(),
                current.revision()).stream().findFirst()
                .orElseGet(() -> requireLocked(current.agentRunId(), current.toolCallId()).ledger());
    }

    private LockedLedgerRow requireLocked(String agentRunId, String toolCallId) {
        String sql = "SELECT " + COLUMNS
                + ", (lease_until IS NOT NULL AND lease_until > clock_timestamp()) AS lease_active "
                + "FROM platform_tool_execution_ledger "
                + "WHERE agent_run_id = ? AND tool_call_id = ? FOR UPDATE";
        return jdbcTemplate.query(
                sql,
                this::mapLocked,
                agentRunId,
                toolCallId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Tool execution ledger entry not found"));
    }

    private LockedLedgerRow mapLocked(ResultSet rs, int rowNum) throws SQLException {
        return new LockedLedgerRow(map(rs, rowNum), rs.getBoolean("lease_active"));
    }

    private ToolExecutionLedger map(ResultSet rs, int rowNum) throws SQLException {
        UUID claimToken = rs.getObject("claim_token", UUID.class);
        return new ToolExecutionLedger(
                rs.getString("id"),
                rs.getString("agent_run_id"),
                rs.getString("run_step_id"),
                rs.getString("tool_name"),
                rs.getString("tool_call_id"),
                rs.getString("idempotency_key"),
                rs.getString("arguments"),
                rs.getString("input_hash"),
                ToolExecutionStatus.valueOf(rs.getString("status")),
                rs.getString("result"),
                rs.getString("result_ref"),
                rs.getString("error"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")),
                claimToken == null ? null : claimToken.toString(),
                rs.getString("claim_owner"),
                instant(rs.getTimestamp("lease_until")),
                rs.getLong("revision"),
                instant(rs.getTimestamp("claimed_at")),
                instant(rs.getTimestamp("updated_at")),
                fromJson(rs.getString("reconciliation_evidence")),
                instant(rs.getTimestamp("resolved_at")),
                rs.getString("resolved_by"),
                rs.getString("resolution_reason"));
    }

    private String toJson(ToolExecutionReconciliationEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize tool reconciliation evidence", error);
        }
    }

    private ToolExecutionReconciliationEvidence fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ToolExecutionReconciliationEvidence.class);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to deserialize tool reconciliation evidence", error);
        }
    }

    private static ToolExecutionClaimDecision decision(
            ToolExecutionClaimDecisionType type,
            ToolExecutionLedger ledger,
            String reason) {
        return new ToolExecutionClaimDecision(type, ledger, null, null, reason);
    }

    private static ToolExecutionTransitionResult transition(
            ToolExecutionTransitionType type,
            ToolExecutionLedger ledger) {
        return new ToolExecutionTransitionResult(type, ledger);
    }

    private static boolean idempotencyKeyConflicts(String requested, String existing) {
        return requested != null && existing != null && !requested.equals(existing);
    }

    private static boolean isTerminal(ToolExecutionStatus status) {
        return status == ToolExecutionStatus.SUCCEEDED
                || status == ToolExecutionStatus.FAILED
                || status == ToolExecutionStatus.TIMED_OUT
                || status == ToolExecutionStatus.CANCELLED;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new IllegalStateException("Transaction returned no result");
        }
        return value;
    }

    private record LockedLedgerRow(ToolExecutionLedger ledger, boolean leaseActive) {
    }
}
