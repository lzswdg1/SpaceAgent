package com.spaceagent.platform.inference.infrastructure.persistence;

import com.spaceagent.platform.inference.domain.ModelCallClaimDecision;
import com.spaceagent.platform.inference.domain.ModelCallClaimDecisionType;
import com.spaceagent.platform.inference.domain.ModelCallClaimRequest;
import com.spaceagent.platform.inference.domain.ModelCallCompletionRequest;
import com.spaceagent.platform.inference.domain.ModelCallFirstChunkRequest;
import com.spaceagent.platform.inference.domain.ModelCallLedger;
import com.spaceagent.platform.inference.domain.ModelCallLedgerRepository;
import com.spaceagent.platform.inference.domain.ModelCallStatus;
import com.spaceagent.platform.inference.domain.ModelCallTransitionResult;
import com.spaceagent.platform.inference.domain.ModelCallTransitionType;
import com.spaceagent.platform.inference.domain.ModelCallUnknownRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** PostgreSQL atomic claim and fenced state transitions for model calls. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresModelCallLedgerRepository implements ModelCallLedgerRepository {

    private static final String COLUMNS = """
            id, agent_run_id, run_step_id, logical_call_id, request_hash, status,
            provider_id, model_id, claim_token, claim_owner, lease_until, revision,
            claimed_at, first_chunk_at, first_chunk_ms, provider_request_id,
            response_payload::text AS response_payload,
            usage_payload::text AS usage_payload, error_code, error_summary,
            created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.spaceagent.platform.shared.api.ExecutionAttributionQuery attribution;

    public PostgresModelCallLedgerRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ModelCallClaimDecision claim(ModelCallClaimRequest request) {
        return required(transactionTemplate.execute(ignored -> claimInTransaction(request)));
    }

    @Override
    public ModelCallTransitionResult complete(ModelCallCompletionRequest request) {
        return required(transactionTemplate.execute(ignored -> completeInTransaction(request)));
    }

    @Override
    public ModelCallTransitionResult recordFirstChunk(ModelCallFirstChunkRequest request) {
        return required(transactionTemplate.execute(ignored -> recordFirstChunkInTransaction(request)));
    }

    @Override
    public ModelCallTransitionResult markUnknown(ModelCallUnknownRequest request) {
        return required(transactionTemplate.execute(ignored -> markUnknownInTransaction(request)));
    }

    @Override
    public Optional<ModelCallLedger> findByRunIdAndLogicalCallId(
            String agentRunId,
            String logicalCallId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_model_call_ledger "
                        + "WHERE agent_run_id = ? AND logical_call_id = ?",
                this::map,
                agentRunId,
                logicalCallId).stream().findFirst();
    }

    @Override
    public List<ModelCallLedger> findByRunId(String agentRunId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_model_call_ledger "
                        + "WHERE agent_run_id = ? ORDER BY created_at, id",
                this::map,
                agentRunId);
    }

    private ModelCallClaimDecision claimInTransaction(ModelCallClaimRequest request) {
        var scope=attribution==null?null:attribution.resolve(request.agentRunId()).orElse(null);
        Optional<ModelCallLedger> inserted = jdbcTemplate.query("""
                WITH claim_time AS (SELECT clock_timestamp() AS now_value)
                INSERT INTO platform_model_call_ledger (
                    id, agent_run_id, run_step_id, logical_call_id, request_hash, status,
                    provider_id, model_id, claim_token, claim_owner, lease_until, revision,
                    claimed_at, created_at, updated_at,usage_tenant_id,usage_actor_id
                )
                SELECT ?, ?, ?, ?, ?, 'RUNNING', ?, ?, CAST(? AS UUID), ?,
                       now_value + (? * INTERVAL '1 second'), 1, now_value, now_value, now_value,?,?
                  FROM claim_time
                ON CONFLICT (agent_run_id, logical_call_id) DO NOTHING
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.id(),
                request.agentRunId(),
                request.runStepId(),
                request.logicalCallId(),
                request.requestHash(),
                request.providerId(),
                request.modelId(),
                request.claimToken(),
                request.claimOwner(),
                request.leaseSeconds(),scope==null?null:scope.tenantId(),scope==null?null:scope.actorId()).stream().findFirst();
        if (inserted.isPresent()) {
            return decision(ModelCallClaimDecisionType.CLAIMED, inserted.get(), null);
        }

        LockedRow locked = requireLocked(request.agentRunId(), request.logicalCallId());
        ModelCallLedger current = locked.ledger();
        if (!request.requestHash().equals(current.requestHash())) {
            return new ModelCallClaimDecision(
                    ModelCallClaimDecisionType.CONFLICT,
                    current,
                    current.requestHash(),
                    request.requestHash(),
                    "logical model call request does not match the persisted claim");
        }
        if (isTerminal(current.status())) {
            return decision(ModelCallClaimDecisionType.REPLAY, current, null);
        }
        if (current.status() == ModelCallStatus.UNKNOWN) {
            return decision(ModelCallClaimDecisionType.UNKNOWN, current, current.errorSummary());
        }
        if (current.status() == ModelCallStatus.RUNNING && locked.leaseActive()) {
            return decision(ModelCallClaimDecisionType.BUSY, current, "model call claim lease is active");
        }
        ModelCallLedger unknown = markExpiredUnknown(
                current,
                "MODEL_CALL_LEASE_EXPIRED",
                "model call lease expired; provider completion is ambiguous");
        return decision(ModelCallClaimDecisionType.UNKNOWN, unknown, unknown.errorSummary());
    }

    private ModelCallTransitionResult completeInTransaction(ModelCallCompletionRequest request) {
        Optional<ModelCallLedger> completed = jdbcTemplate.query("""
                UPDATE platform_model_call_ledger
                   SET status = ?,
                       provider_request_id = ?,
                       response_payload = CAST(? AS JSONB),
                       usage_payload = CAST(? AS JSONB),
                       error_code = ?,
                       error_summary = ?,
                       revision = revision + 1,
                       updated_at = clock_timestamp()
                 WHERE agent_run_id = ?
                   AND logical_call_id = ?
                   AND status = 'RUNNING'
                   AND claim_token = CAST(? AS UUID)
                   AND revision = ?
                   AND lease_until > clock_timestamp()
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.status().name(),
                request.providerRequestId(),
                request.responsePayload(),
                request.usagePayload(),
                request.errorCode(),
                request.errorSummary(),
                request.agentRunId(),
                request.logicalCallId(),
                request.claimToken(),
                request.expectedRevision()).stream().findFirst();
        if (completed.isPresent()) {
            return transition(ModelCallTransitionType.APPLIED, completed.get());
        }

        LockedRow locked = requireLocked(request.agentRunId(), request.logicalCallId());
        ModelCallLedger current = locked.ledger();
        if (isTerminal(current.status())) {
            return transition(ModelCallTransitionType.CURRENT_TERMINAL, current);
        }
        if (current.status() == ModelCallStatus.UNKNOWN) {
            return transition(ModelCallTransitionType.CURRENT_UNKNOWN, current);
        }
        if (current.status() == ModelCallStatus.RUNNING && !locked.leaseActive()) {
            ModelCallLedger unknown = markExpiredUnknown(
                    current,
                    "MODEL_CALL_LATE_COMPLETION",
                    "model result arrived after the claim lease expired");
            return transition(ModelCallTransitionType.CURRENT_UNKNOWN, unknown);
        }
        return transition(ModelCallTransitionType.CLAIM_LOST, current);
    }

    private ModelCallTransitionResult recordFirstChunkInTransaction(ModelCallFirstChunkRequest request) {
        Optional<ModelCallLedger> updated = jdbcTemplate.query("""
                WITH observed AS (SELECT clock_timestamp() AS at)
                UPDATE platform_model_call_ledger call
                   SET first_chunk_at = observed.at,
                       first_chunk_ms = ?,
                       updated_at = observed.at
                  FROM observed
                 WHERE call.agent_run_id = ?
                   AND call.logical_call_id = ?
                   AND call.status = 'RUNNING'
                   AND call.claim_token = CAST(? AS UUID)
                   AND call.revision = ?
                   AND call.lease_until > observed.at
                   AND call.first_chunk_at IS NULL
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.elapsedMillis(), request.agentRunId(), request.logicalCallId(), request.claimToken(),
                request.expectedRevision()).stream().findFirst();
        if (updated.isPresent()) {
            return transition(ModelCallTransitionType.APPLIED, updated.get());
        }
        ModelCallLedger current = requireLocked(
                request.agentRunId(), request.logicalCallId()).ledger();
        if (current.firstChunkAt() != null
                && request.claimToken().equals(current.claimToken())
                && request.expectedRevision() == current.revision()) {
            return transition(ModelCallTransitionType.APPLIED, current);
        }
        if (isTerminal(current.status())) {
            return transition(ModelCallTransitionType.CURRENT_TERMINAL, current);
        }
        if (current.status() == ModelCallStatus.UNKNOWN) {
            return transition(ModelCallTransitionType.CURRENT_UNKNOWN, current);
        }
        return transition(ModelCallTransitionType.CLAIM_LOST, current);
    }

    private ModelCallTransitionResult markUnknownInTransaction(ModelCallUnknownRequest request) {
        Optional<ModelCallLedger> updated = jdbcTemplate.query("""
                UPDATE platform_model_call_ledger
                   SET status = 'UNKNOWN',
                       claim_token = NULL,
                       error_code = ?,
                       error_summary = ?,
                       revision = revision + 1,
                       updated_at = clock_timestamp()
                 WHERE agent_run_id = ?
                   AND logical_call_id = ?
                   AND status = 'RUNNING'
                   AND claim_token = CAST(? AS UUID)
                   AND revision = ?
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.errorCode(),
                request.errorSummary(),
                request.agentRunId(),
                request.logicalCallId(),
                request.claimToken(),
                request.expectedRevision()).stream().findFirst();
        if (updated.isPresent()) {
            return transition(ModelCallTransitionType.APPLIED, updated.get());
        }

        ModelCallLedger current = requireLocked(request.agentRunId(), request.logicalCallId()).ledger();
        if (isTerminal(current.status())) {
            return transition(ModelCallTransitionType.CURRENT_TERMINAL, current);
        }
        if (current.status() == ModelCallStatus.UNKNOWN) {
            return transition(ModelCallTransitionType.CURRENT_UNKNOWN, current);
        }
        return transition(ModelCallTransitionType.CLAIM_LOST, current);
    }

    private ModelCallLedger markExpiredUnknown(
            ModelCallLedger current,
            String errorCode,
            String errorSummary) {
        return jdbcTemplate.query("""
                UPDATE platform_model_call_ledger
                   SET status = 'UNKNOWN',
                       claim_token = NULL,
                       error_code = ?,
                       error_summary = ?,
                       revision = revision + 1,
                       updated_at = clock_timestamp()
                 WHERE id = ?
                   AND status = 'RUNNING'
                   AND revision = ?
                   AND lease_until <= clock_timestamp()
                RETURNING """ + " " + COLUMNS,
                this::map,
                errorCode,
                errorSummary,
                current.id(),
                current.revision()).stream().findFirst()
                .orElseGet(() -> requireLocked(current.agentRunId(), current.logicalCallId()).ledger());
    }

    private LockedRow requireLocked(String agentRunId, String logicalCallId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + ", (lease_until IS NOT NULL AND lease_until > clock_timestamp()) AS lease_active "
                        + "FROM platform_model_call_ledger "
                        + "WHERE agent_run_id = ? AND logical_call_id = ? FOR UPDATE",
                (rs, rowNum) -> new LockedRow(map(rs, rowNum), rs.getBoolean("lease_active")),
                agentRunId,
                logicalCallId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("model call ledger row disappeared"));
    }

    private ModelCallLedger map(ResultSet rs, int rowNum) throws SQLException {
        return new ModelCallLedger(
                rs.getString("id"),
                rs.getString("agent_run_id"),
                rs.getString("run_step_id"),
                rs.getString("logical_call_id"),
                rs.getString("request_hash"),
                ModelCallStatus.valueOf(rs.getString("status")),
                rs.getString("provider_id"),
                rs.getString("model_id"),
                rs.getString("claim_token"),
                rs.getString("claim_owner"),
                instant(rs, "lease_until"),
                rs.getLong("revision"),
                instant(rs, "claimed_at"),
                instant(rs, "first_chunk_at"),
                nullableLong(rs, "first_chunk_ms"),
                rs.getString("provider_request_id"),
                rs.getString("response_payload"),
                rs.getString("usage_payload"),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static boolean isTerminal(ModelCallStatus status) {
        return status == ModelCallStatus.SUCCEEDED
                || status == ModelCallStatus.FAILED
                || status == ModelCallStatus.TIMED_OUT
                || status == ModelCallStatus.CANCELLED;
    }

    private static ModelCallClaimDecision decision(
            ModelCallClaimDecisionType type,
            ModelCallLedger ledger,
            String reason) {
        return new ModelCallClaimDecision(type, ledger, null, null, reason);
    }

    private static ModelCallTransitionResult transition(
            ModelCallTransitionType type,
            ModelCallLedger ledger) {
        return new ModelCallTransitionResult(type, ledger);
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new IllegalStateException("model call ledger transaction returned no result");
        }
        return value;
    }

    private record LockedRow(ModelCallLedger ledger, boolean leaseActive) {
    }
}
