package com.spaceagent.platform.governance.infrastructure.persistence;

import com.spaceagent.platform.governance.domain.ApprovalRequest;
import com.spaceagent.platform.governance.domain.ApprovalScope;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.governance.domain.GovernancePolicy;
import com.spaceagent.platform.governance.domain.GovernanceRepository;
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

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresGovernanceRepository implements GovernanceRepository {

    private static final String POLICY_COLUMNS = """
            tenant_id, require_coding_file_approval, require_command_approval,
            require_automation_approval, require_network_approval,
            require_source_merge_approval,
            separation_of_duties, approval_ttl_seconds, revision, updated_by, updated_at
            """;
    private static final String APPROVAL_COLUMNS = """
            id, tenant_id, requested_by, action_type, resource_type, resource_id,
            operation_hash, summary, state, expires_at, decided_by, decided_at,
            decision_note, consumed_at, revision, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresGovernanceRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Optional<GovernancePolicy> findPolicy(String tenantId) {
        return jdbc.query(
                "SELECT " + POLICY_COLUMNS
                        + " FROM platform_governance_policies WHERE tenant_id = ?",
                this::mapPolicy, tenantId).stream().findFirst();
    }

    @Override
    public Optional<GovernancePolicy> savePolicy(
            GovernancePolicy policy, long expectedRevision) {
        if (expectedRevision == 0) {
            return jdbc.query("""
                    INSERT INTO platform_governance_policies (
                        tenant_id, require_coding_file_approval, require_command_approval,
                        require_automation_approval, require_network_approval,
                        require_source_merge_approval,
                        separation_of_duties, approval_ttl_seconds, revision,
                        updated_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                    ON CONFLICT (tenant_id) DO NOTHING
                    RETURNING """ + " " + POLICY_COLUMNS,
                    this::mapPolicy,
                    policy.tenantId(), policy.requireCodingFileApproval(),
                    policy.requireCommandApproval(), policy.requireAutomationApproval(),
                    policy.requireNetworkApproval(),
                    policy.requireSourceMergeApproval(), policy.separationOfDuties(),
                    policy.approvalTtlSeconds(), policy.updatedBy(),
                    timestamp(policy.updatedAt()), timestamp(policy.updatedAt()))
                    .stream().findFirst();
        }
        return jdbc.query("""
                UPDATE platform_governance_policies
                   SET require_coding_file_approval = ?, require_command_approval = ?,
                       require_automation_approval = ?, require_network_approval = ?,
                       require_source_merge_approval = ?,
                       separation_of_duties = ?, approval_ttl_seconds = ?,
                       revision = revision + 1, updated_by = ?, updated_at = ?
                 WHERE tenant_id = ? AND revision = ?
                RETURNING """ + " " + POLICY_COLUMNS,
                this::mapPolicy,
                policy.requireCodingFileApproval(), policy.requireCommandApproval(),
                policy.requireAutomationApproval(),
                policy.requireNetworkApproval(), policy.requireSourceMergeApproval(),
                policy.separationOfDuties(), policy.approvalTtlSeconds(),
                policy.updatedBy(), timestamp(policy.updatedAt()),
                policy.tenantId(), expectedRevision).stream().findFirst();
    }

    @Override
    public Optional<ApprovalRequest> findApproval(String tenantId, String approvalId) {
        if (!isUuid(approvalId)) {
            return Optional.empty();
        }
        return jdbc.query(
                "SELECT " + APPROVAL_COLUMNS
                        + " FROM platform_approval_requests WHERE tenant_id = ? AND id = CAST(? AS UUID)",
                this::mapApproval, tenantId, approvalId).stream().findFirst();
    }

    @Override
    public Optional<ApprovalRequest> findPending(ApprovalScope scope) {
        return jdbc.query("SELECT " + APPROVAL_COLUMNS + " FROM platform_approval_requests "
                        + "WHERE tenant_id = ? AND requested_by = ? AND action_type = ? "
                        + "AND resource_type = ? AND resource_id = ? AND operation_hash = ? "
                        + "AND state = 'PENDING' ORDER BY created_at DESC LIMIT 1",
                this::mapApproval,
                scope.tenantId(), scope.requestedBy(), scope.actionType().name(),
                scope.resourceType(), scope.resourceId(), scope.operationHash())
                .stream().findFirst();
    }

    @Override
    public ApprovalRequest createOrFindPending(ApprovalRequest request) {
        ApprovalRequest result = transactions.execute(ignored -> {
            Optional<ApprovalRequest> inserted = jdbc.query("""
                    INSERT INTO platform_approval_requests (
                        id, tenant_id, requested_by, action_type, resource_type, resource_id,
                        operation_hash, summary, state, expires_at, revision, created_at, updated_at)
                    VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 1, ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING """ + " " + APPROVAL_COLUMNS,
                    this::mapApproval,
                    request.id(), request.tenantId(), request.requestedBy(),
                    request.actionType().name(), request.resourceType(), request.resourceId(),
                    request.operationHash(), request.summary(), timestamp(request.expiresAt()),
                    timestamp(request.createdAt()), timestamp(request.updatedAt()))
                    .stream().findFirst();
            return inserted.orElseGet(() -> findPending(request.scope())
                    .orElseThrow(() -> new IllegalStateException(
                            "Concurrent pending approval could not be resolved")));
        });
        if (result == null) {
            throw new IllegalStateException("Approval insert transaction returned no result");
        }
        return result;
    }

    @Override
    public List<ApprovalRequest> listApprovals(
            String tenantId, ApprovalState state, int limit, Instant now) {
        expireTenant(tenantId, now);
        if (state == null) {
            return jdbc.query("SELECT " + APPROVAL_COLUMNS
                            + " FROM platform_approval_requests WHERE tenant_id = ? "
                            + "ORDER BY created_at DESC, id LIMIT ?",
                    this::mapApproval, tenantId, limit);
        }
        return jdbc.query("SELECT " + APPROVAL_COLUMNS
                        + " FROM platform_approval_requests WHERE tenant_id = ? AND state = ? "
                        + "ORDER BY created_at DESC, id LIMIT ?",
                this::mapApproval, tenantId, state.name(), limit);
    }

    @Override
    public List<ApprovalRequest> listApprovalsByRequester(
            String tenantId, String requestedBy, String resourceType, String resourceId,
            ApprovalState state, int limit, Instant now) {
        expireTenant(tenantId, now);
        if (state == null) {
            return jdbc.query("SELECT " + APPROVAL_COLUMNS
                            + " FROM platform_approval_requests WHERE tenant_id = ? "
                            + "AND requested_by = ? AND resource_type = ? AND resource_id = ? "
                            + "ORDER BY created_at DESC, id LIMIT ?",
                    this::mapApproval, tenantId, requestedBy, resourceType, resourceId, limit);
        }
        return jdbc.query("SELECT " + APPROVAL_COLUMNS
                        + " FROM platform_approval_requests WHERE tenant_id = ? "
                        + "AND requested_by = ? AND resource_type = ? AND resource_id = ? "
                        + "AND state = ? "
                        + "ORDER BY created_at DESC, id LIMIT ?",
                this::mapApproval, tenantId, requestedBy, resourceType, resourceId,
                state.name(), limit);
    }

    @Override
    public Optional<ApprovalRequest> decide(
            String tenantId,
            String approvalId,
            long expectedRevision,
            ApprovalState decision,
            String decidedBy,
            String note,
            Instant now) {
        if (!isUuid(approvalId)) {
            return Optional.empty();
        }
        return jdbc.query("""
                UPDATE platform_approval_requests
                   SET state = ?, decided_by = ?, decided_at = ?, decision_note = ?,
                       revision = revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = CAST(? AS UUID) AND state = 'PENDING'
                   AND revision = ? AND expires_at > ?
                RETURNING """ + " " + APPROVAL_COLUMNS,
                this::mapApproval,
                decision.name(), decidedBy, timestamp(now), note, timestamp(now),
                tenantId, approvalId, expectedRevision, timestamp(now))
                .stream().findFirst();
    }

    @Override
    public Optional<ApprovalRequest> consume(
            String approvalId, ApprovalScope scope, Instant now) {
        if (!isUuid(approvalId)) {
            return Optional.empty();
        }
        return jdbc.query("""
                UPDATE platform_approval_requests
                   SET state = 'CONSUMED', consumed_at = ?,
                       revision = revision + 1, updated_at = ?
                 WHERE id = CAST(? AS UUID) AND tenant_id = ? AND requested_by = ?
                   AND action_type = ? AND resource_type = ? AND resource_id = ?
                   AND operation_hash = ? AND state = 'APPROVED' AND expires_at > ?
                RETURNING """ + " " + APPROVAL_COLUMNS,
                this::mapApproval,
                timestamp(now), timestamp(now), approvalId, scope.tenantId(),
                scope.requestedBy(), scope.actionType().name(), scope.resourceType(),
                scope.resourceId(), scope.operationHash(), timestamp(now))
                .stream().findFirst();
    }

    @Override
    public Optional<ApprovalRequest> expire(
            String tenantId, String approvalId, long expectedRevision, Instant now) {
        if (!isUuid(approvalId)) {
            return Optional.empty();
        }
        return jdbc.query("""
                UPDATE platform_approval_requests
                   SET state = 'EXPIRED', revision = revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = CAST(? AS UUID) AND revision = ?
                   AND state IN ('PENDING', 'APPROVED') AND expires_at <= ?
                RETURNING """ + " " + APPROVAL_COLUMNS,
                this::mapApproval,
                timestamp(now), tenantId, approvalId, expectedRevision, timestamp(now))
                .stream().findFirst();
    }

    @Override
    public Optional<ApprovalRequest> cancel(
            String tenantId,
            String approvalId,
            long expectedRevision,
            String requestedBy,
            Instant now) {
        if (!isUuid(approvalId)) {
            return Optional.empty();
        }
        return jdbc.query("""
                UPDATE platform_approval_requests
                   SET state = 'CANCELLED', revision = revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = CAST(? AS UUID) AND revision = ?
                   AND requested_by = ? AND state = 'PENDING'
                RETURNING """ + " " + APPROVAL_COLUMNS,
                this::mapApproval,
                timestamp(now), tenantId, approvalId, expectedRevision, requestedBy)
                .stream().findFirst();
    }

    private void expireTenant(String tenantId, Instant now) {
        jdbc.update("""
                UPDATE platform_approval_requests
                   SET state = 'EXPIRED', revision = revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND state IN ('PENDING', 'APPROVED') AND expires_at <= ?
                """, timestamp(now), tenantId, timestamp(now));
    }

    private GovernancePolicy mapPolicy(ResultSet row, int rowNumber) throws SQLException {
        return new GovernancePolicy(
                row.getString("tenant_id"), row.getBoolean("require_coding_file_approval"),
                row.getBoolean("require_command_approval"),
                row.getBoolean("require_automation_approval"),
                row.getBoolean("require_network_approval"),
                row.getBoolean("require_source_merge_approval"),
                row.getBoolean("separation_of_duties"), row.getInt("approval_ttl_seconds"),
                row.getLong("revision"), row.getString("updated_by"),
                instant(row.getTimestamp("updated_at")));
    }

    private ApprovalRequest mapApproval(ResultSet row, int rowNumber) throws SQLException {
        return new ApprovalRequest(
                row.getString("id"), row.getString("tenant_id"),
                row.getString("requested_by"),
                GovernanceActionType.valueOf(row.getString("action_type")),
                row.getString("resource_type"), row.getString("resource_id"),
                row.getString("operation_hash"), row.getString("summary"),
                ApprovalState.valueOf(row.getString("state")),
                instant(row.getTimestamp("expires_at")), row.getString("decided_by"),
                instant(row.getTimestamp("decided_at")), row.getString("decision_note"),
                instant(row.getTimestamp("consumed_at")), row.getLong("revision"),
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException | NullPointerException error) {
            return false;
        }
    }
}
