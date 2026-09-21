package com.spaceagent.platform.tooling.infrastructure.persistence;

import com.spaceagent.platform.tooling.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresMcpInvocationLedgerRepository
        implements McpInvocationLedgerRepository, McpCheckoutGrantRepository {
    private static final String COLUMNS = """
            id, tenant_id, user_id, connection_id, operation_key, idempotency_key_hash,
            tool_name, arguments_json::text AS arguments_json, input_hash, status,
            result_json::text AS result_json, error_code, claim_token, claim_owner,
            lease_until, revision, started_at, updated_at, completed_at
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresMcpInvocationLedgerRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ClaimDecision claim(ClaimRequest request) {
        return required(transactions.execute(ignored -> claimInTransaction(request)));
    }

    @Override
    public Transition complete(CompleteRequest request) {
        return required(transactions.execute(ignored -> completeInTransaction(request)));
    }

    @Override
    public Transition markUnknown(UnknownRequest request) {
        return required(transactions.execute(ignored -> unknownInTransaction(request)));
    }

    @Override
    public McpInvocationTransitionType completeWithGrant(CompleteGrantRequest request) {
        return required(transactions.execute(ignored -> completeGrantInTransaction(request)));
    }

    @Override
    public Optional<StoredGrant> findAvailable(GrantQuery query) {
        return required(transactions.execute(ignored -> findAvailableInTransaction(query)));
    }

    @Override
    public void consume(GrantQuery query) {
        transactions.executeWithoutResult(ignored -> consumeInTransaction(query));
    }

    @Override
    public int redactExpired() {
        return required(transactions.execute(ignored -> jdbc.update("""
                UPDATE platform_mcp_invocation_ledger
                   SET result_json=NULL,checkout_consumed_at=COALESCE(
                       checkout_consumed_at,clock_timestamp()),
                       revision=revision+1,updated_at=clock_timestamp()
                 WHERE status='SUCCEEDED' AND checkout_consumed_at IS NULL
                   AND checkout_expires_at<=clock_timestamp() AND result_json IS NOT NULL
                """)));
    }

    private ClaimDecision claimInTransaction(ClaimRequest request) {
        Optional<McpInvocationLedger> inserted = jdbc.query("""
                WITH claim_time AS (SELECT clock_timestamp() AS now_value)
                INSERT INTO platform_mcp_invocation_ledger(
                    id,tenant_id,user_id,connection_id,operation_key,idempotency_key_hash,
                    tool_name,arguments_json,input_hash,status,claim_token,claim_owner,
                    lease_until,revision,started_at,updated_at)
                SELECT CAST(? AS UUID),?,?,CAST(? AS UUID),?,?,?,CAST(? AS JSONB),?,
                       'RUNNING',CAST(? AS UUID),?,now_value+(?*INTERVAL '1 second'),
                       1,now_value,now_value
                FROM claim_time
                ON CONFLICT(tenant_id,user_id,operation_key,idempotency_key_hash) DO NOTHING
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.id(), request.tenantId(), request.userId(), request.connectionId(),
                request.operationKey(), request.idempotencyKeyHash(), request.toolName(),
                request.argumentsJson(), request.inputHash(), request.claimToken(),
                request.claimOwner(), request.leaseSeconds()).stream().findFirst();
        if (inserted.isPresent()) {
            return new ClaimDecision(McpInvocationClaimType.CLAIMED, inserted.get());
        }
        Locked current = lockByLogicalKey(request);
        if (!current.ledger().inputHash().equals(request.inputHash())) {
            return new ClaimDecision(McpInvocationClaimType.CONFLICT, current.ledger());
        }
        if (terminal(current.ledger().status())) {
            return new ClaimDecision(McpInvocationClaimType.REPLAY, current.ledger());
        }
        if (current.ledger().status() == McpInvocationStatus.UNKNOWN) {
            return new ClaimDecision(McpInvocationClaimType.UNKNOWN, current.ledger());
        }
        if (current.leaseActive()) {
            return new ClaimDecision(McpInvocationClaimType.BUSY, current.ledger());
        }
        McpInvocationLedger unknown = markExpiredUnknown(current.ledger());
        return new ClaimDecision(McpInvocationClaimType.UNKNOWN, unknown);
    }

    private Transition completeInTransaction(CompleteRequest request) {
        Optional<McpInvocationLedger> completed = jdbc.query("""
                UPDATE platform_mcp_invocation_ledger
                   SET status=?,result_json=CAST(? AS JSONB),error_code=?,
                       revision=revision+1,updated_at=clock_timestamp(),
                       completed_at=clock_timestamp()
                 WHERE id=CAST(? AS UUID) AND status='RUNNING'
                   AND claim_token=CAST(? AS UUID) AND revision=?
                   AND lease_until>clock_timestamp()
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.status().name(), request.resultJson(), request.errorCode(), request.id(),
                request.claimToken(), request.expectedRevision()).stream().findFirst();
        if (completed.isPresent()) {
            return new Transition(McpInvocationTransitionType.APPLIED, completed.get());
        }
        Locked current = lockById(request.id());
        if (terminal(current.ledger().status())) {
            return new Transition(McpInvocationTransitionType.CURRENT_TERMINAL, current.ledger());
        }
        if (current.ledger().status() == McpInvocationStatus.UNKNOWN) {
            return new Transition(McpInvocationTransitionType.CURRENT_UNKNOWN, current.ledger());
        }
        if (!current.leaseActive()) {
            return new Transition(McpInvocationTransitionType.CURRENT_UNKNOWN,
                    markExpiredUnknown(current.ledger()));
        }
        return new Transition(McpInvocationTransitionType.CLAIM_LOST, current.ledger());
    }

    private Transition unknownInTransaction(UnknownRequest request) {
        Optional<McpInvocationLedger> changed = jdbc.query("""
                UPDATE platform_mcp_invocation_ledger
                   SET status='UNKNOWN',claim_token=NULL,error_code=?,revision=revision+1,
                       updated_at=clock_timestamp()
                 WHERE id=CAST(? AS UUID) AND status='RUNNING'
                   AND claim_token=CAST(? AS UUID) AND revision=?
                RETURNING """ + " " + COLUMNS,
                this::map,
                request.errorCode(), request.id(), request.claimToken(),
                request.expectedRevision()).stream().findFirst();
        if (changed.isPresent()) {
            return new Transition(McpInvocationTransitionType.APPLIED, changed.get());
        }
        McpInvocationLedger current = lockById(request.id()).ledger();
        if (terminal(current.status())) {
            return new Transition(McpInvocationTransitionType.CURRENT_TERMINAL, current);
        }
        if (current.status() == McpInvocationStatus.UNKNOWN) {
            return new Transition(McpInvocationTransitionType.CURRENT_UNKNOWN, current);
        }
        return new Transition(McpInvocationTransitionType.CLAIM_LOST, current);
    }

    private McpInvocationTransitionType completeGrantInTransaction(
            CompleteGrantRequest request) {
        int updated = jdbc.update("""
                UPDATE platform_mcp_invocation_ledger
                   SET status='SUCCEEDED',result_json=CAST(? AS JSONB),error_code=NULL,
                       checkout_expires_at=?,checkout_consumed_at=NULL,
                       checkout_evidence_hash=?,revision=revision+1,
                       updated_at=clock_timestamp(),completed_at=clock_timestamp()
                 WHERE id=CAST(? AS UUID) AND tenant_id=? AND user_id=?
                   AND status='RUNNING' AND claim_token=CAST(? AS UUID) AND revision=?
                   AND lease_until>clock_timestamp()
                """, request.encryptedGrantJson(), Timestamp.from(request.expiresAt()),
                request.evidenceHash(), request.invocationId(), request.tenantId(),
                request.userId(), request.claimToken(), request.expectedRevision());
        if (updated == 1) return McpInvocationTransitionType.APPLIED;
        Locked current = lockById(request.invocationId());
        if (terminal(current.ledger().status())) {
            return McpInvocationTransitionType.CURRENT_TERMINAL;
        }
        if (current.ledger().status() == McpInvocationStatus.UNKNOWN) {
            return McpInvocationTransitionType.CURRENT_UNKNOWN;
        }
        if (!current.leaseActive()) {
            markExpiredUnknown(current.ledger());
            return McpInvocationTransitionType.CURRENT_UNKNOWN;
        }
        return McpInvocationTransitionType.CLAIM_LOST;
    }

    private Optional<StoredGrant> findAvailableInTransaction(GrantQuery query) {
        jdbc.update("""
                UPDATE platform_mcp_invocation_ledger
                   SET result_json=NULL,checkout_consumed_at=COALESCE(
                       checkout_consumed_at,clock_timestamp()),
                       revision=revision+1,updated_at=clock_timestamp()
                 WHERE id=CAST(? AS UUID) AND tenant_id=? AND user_id=?
                   AND status='SUCCEEDED' AND checkout_consumed_at IS NULL
                   AND checkout_expires_at<=clock_timestamp() AND result_json IS NOT NULL
                """, query.invocationId(), query.tenantId(), query.userId());
        return jdbc.query("""
                SELECT id,result_json::text AS encrypted_grant,checkout_expires_at
                  FROM platform_mcp_invocation_ledger
                 WHERE id=CAST(? AS UUID) AND tenant_id=? AND user_id=?
                   AND status='SUCCEEDED' AND checkout_consumed_at IS NULL
                   AND checkout_expires_at>clock_timestamp() AND result_json IS NOT NULL
                """, (rs, row) -> new StoredGrant(
                        rs.getString("id"), rs.getString("encrypted_grant"),
                        rs.getTimestamp("checkout_expires_at").toInstant()),
                query.invocationId(), query.tenantId(), query.userId()).stream().findFirst();
    }

    private void consumeInTransaction(GrantQuery query) {
        jdbc.update("""
                UPDATE platform_mcp_invocation_ledger
                   SET result_json=NULL,checkout_consumed_at=COALESCE(
                       checkout_consumed_at,clock_timestamp()),
                       revision=revision+1,updated_at=clock_timestamp()
                 WHERE id=CAST(? AS UUID) AND tenant_id=? AND user_id=?
                   AND status='SUCCEEDED' AND checkout_expires_at IS NOT NULL
                   AND checkout_consumed_at IS NULL AND result_json IS NOT NULL
                """, query.invocationId(), query.tenantId(), query.userId());
    }

    private McpInvocationLedger markExpiredUnknown(McpInvocationLedger current) {
        return jdbc.query("""
                UPDATE platform_mcp_invocation_ledger
                   SET status='UNKNOWN',claim_token=NULL,error_code='MCP_INVOCATION_LEASE_EXPIRED',
                       revision=revision+1,updated_at=clock_timestamp()
                 WHERE id=CAST(? AS UUID) AND status='RUNNING' AND revision=?
                   AND lease_until<=clock_timestamp()
                RETURNING """ + " " + COLUMNS,
                this::map, current.id(), current.revision()).stream().findFirst()
                .orElseGet(() -> lockById(current.id()).ledger());
    }

    private Locked lockByLogicalKey(ClaimRequest request) {
        return jdbc.query("SELECT " + COLUMNS
                        + ",(lease_until>clock_timestamp()) AS lease_active"
                        + " FROM platform_mcp_invocation_ledger"
                        + " WHERE tenant_id=? AND user_id=? AND operation_key=?"
                        + " AND idempotency_key_hash=? FOR UPDATE",
                this::mapLocked, request.tenantId(), request.userId(), request.operationKey(),
                request.idempotencyKeyHash()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP invocation not found"));
    }

    private Locked lockById(String id) {
        return jdbc.query("SELECT " + COLUMNS
                        + ",(lease_until>clock_timestamp()) AS lease_active"
                        + " FROM platform_mcp_invocation_ledger WHERE id=CAST(? AS UUID) FOR UPDATE",
                this::mapLocked, id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP invocation not found"));
    }

    private Locked mapLocked(ResultSet rs, int row) throws SQLException {
        return new Locked(map(rs, row), rs.getBoolean("lease_active"));
    }

    private McpInvocationLedger map(ResultSet rs, int row) throws SQLException {
        UUID claim = rs.getObject("claim_token", UUID.class);
        return new McpInvocationLedger(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("user_id"),
                rs.getString("connection_id"), rs.getString("operation_key"),
                rs.getString("idempotency_key_hash"), rs.getString("tool_name"),
                rs.getString("arguments_json"), rs.getString("input_hash"),
                McpInvocationStatus.valueOf(rs.getString("status")), rs.getString("result_json"),
                rs.getString("error_code"), claim == null ? null : claim.toString(),
                rs.getString("claim_owner"), instant(rs.getTimestamp("lease_until")),
                rs.getLong("revision"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("completed_at")));
    }

    private static boolean terminal(McpInvocationStatus status) {
        return status == McpInvocationStatus.SUCCEEDED || status == McpInvocationStatus.FAILED;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static <T> T required(T value) {
        if (value == null) throw new IllegalStateException("Transaction returned no result");
        return value;
    }

    private record Locked(McpInvocationLedger ledger, boolean leaseActive) {
    }
}
