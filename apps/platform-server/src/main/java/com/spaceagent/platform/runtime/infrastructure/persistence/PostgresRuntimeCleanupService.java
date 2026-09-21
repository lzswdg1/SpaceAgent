package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.api.RuntimeCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresRuntimeCleanupService implements RuntimeCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    public PostgresRuntimeCleanupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override @Transactional
    public QuiesceView quiesceOrganization(String organizationId) {
        LeaseWindow window = jdbc.queryForObject("""
                SELECT clock_timestamp() AS database_now, max(lease.lease_until) AS max_lease_until
                FROM platform_run_worker_leases lease
                JOIN platform_agent_runs run ON run.id = lease.agent_run_id
                WHERE run.tenant_id = ?
                """, (rs, row) -> new LeaseWindow(
                rs.getTimestamp("database_now").toInstant(),
                instant(rs.getTimestamp("max_lease_until"))), organizationId);
        Instant codingLease = jdbc.queryForObject(
                "SELECT max(lease_until) FROM platform_project_coding_jobs "
                        + "WHERE tenant_id=? AND state='RUNNING'",
                (rs, row) -> instant(rs.getTimestamp(1)), organizationId);
        Instant handoffLease = jdbc.queryForObject(
                "SELECT max(lease_until) FROM platform_project_run_handoffs "
                        + "WHERE tenant_id=? AND state='FINALIZING'",
                (rs, row) -> instant(rs.getTimestamp(1)), organizationId);
        jdbc.update("""
                UPDATE platform_project_run_handoffs SET state='BLOCKED',
                    safe_error_code='PROJECT_HANDOFF_CLEANUP_QUIESCE',claim_owner=NULL,
                    claim_token=NULL,lease_until=NULL,fencing_token=fencing_token+1,
                    revision=revision+1,updated_at=clock_timestamp(),completed_at=clock_timestamp()
                 WHERE tenant_id=? AND state IN('PENDING','ACTIVE','READY_TO_FINALIZE','FINALIZING')
                """, organizationId);
        jdbc.update("""
                UPDATE platform_project_coding_jobs SET state='BLOCKED',
                    safe_error_code='PROJECT_CODING_CLEANUP_QUIESCE',claim_owner=NULL,
                    claim_token=NULL,lease_until=NULL,fencing_token=fencing_token+1,
                    revision=revision+1,updated_at=clock_timestamp(),completed_at=clock_timestamp()
                 WHERE tenant_id=? AND state IN('PENDING','RUNNING','WAITING_APPROVAL')
                """, organizationId);
        jdbc.update("""
                UPDATE platform_project_plan_executions SET state='BLOCKED',
                    safe_error_code='PROJECT_PLAN_EXECUTION_CLEANUP_QUIESCE',
                    revision=revision+1,updated_at=clock_timestamp(),completed_at=clock_timestamp()
                 WHERE tenant_id=? AND state IN('READY','RUNNING','PAUSING','PAUSED','CANCELLING')
                """, organizationId);
        jdbc.update("""
                UPDATE platform_runtime_continuations continuation
                SET state = 'CANCELLED', claim_token = NULL, claim_owner = NULL,
                    fencing_token = NULL, lease_until = NULL,
                    last_error = 'Organization cleanup quiesce',
                    revision = revision + 1, updated_at = clock_timestamp(),
                    completed_at = clock_timestamp()
                WHERE continuation.agent_run_id IN (
                    SELECT id FROM platform_agent_runs WHERE tenant_id = ?)
                  AND continuation.state IN ('PENDING', 'CLAIMED')
                """, organizationId);
        jdbc.update("""
                UPDATE platform_agent_runs
                SET state = 'CANCELLED', failure_reason = 'Organization cleanup quiesce',
                    revision = revision + 1, updated_at = clock_timestamp(),
                    completed_at = COALESCE(completed_at, clock_timestamp())
                WHERE tenant_id = ?
                  AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """, organizationId);
        jdbc.update("""
                UPDATE platform_run_worker_leases lease
                SET released_at = COALESCE(released_at, clock_timestamp()),
                    revision = revision + 1, heartbeat_at = clock_timestamp()
                WHERE lease.agent_run_id IN (
                    SELECT id FROM platform_agent_runs WHERE tenant_id = ?)
                  AND lease.released_at IS NULL
                """, organizationId);
        Instant maximumLease = max(max(window == null ? null : window.maxLeaseUntil(), codingLease), handoffLease);
        if (window != null && maximumLease != null && maximumLease.isAfter(window.databaseNow())) {
            return new QuiesceView(false, maximumLease.plusSeconds(1));
        }
        return new QuiesceView(true, null);
    }

    @Override @Transactional
    public void purgeOrganization(String organizationId) {
        jdbc.update("DELETE FROM platform_project_run_handoffs WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_project_coding_jobs WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_project_plan_step_assignments WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_project_plan_executions WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_agent_reviews WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_agent_delegations WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_agent_runs WHERE tenant_id = ?", organizationId);
    }

    @Override @Transactional
    public QuiesceView quiesceUser(String userId) {
        LeaseWindow window = jdbc.queryForObject("""
                SELECT clock_timestamp() AS database_now, max(lease.lease_until) AS max_lease_until
                FROM platform_run_worker_leases lease
                JOIN platform_agent_runs run ON run.id = lease.agent_run_id
                WHERE run.owner_id = ?
                """, (rs, row) -> new LeaseWindow(rs.getTimestamp("database_now").toInstant(),
                instant(rs.getTimestamp("max_lease_until"))), userId);
        Instant codingLease = jdbc.queryForObject(
                "SELECT max(lease_until) FROM platform_project_coding_jobs "
                        + "WHERE owner_id=? AND state='RUNNING'",
                (rs, row) -> instant(rs.getTimestamp(1)), userId);
        Instant handoffLease = jdbc.queryForObject(
                "SELECT max(lease_until) FROM platform_project_run_handoffs "
                        + "WHERE owner_id=? AND state='FINALIZING'",
                (rs, row) -> instant(rs.getTimestamp(1)), userId);
        jdbc.update("""
                UPDATE platform_project_run_handoffs SET state='BLOCKED',
                    safe_error_code='PROJECT_HANDOFF_CLEANUP_QUIESCE',claim_owner=NULL,
                    claim_token=NULL,lease_until=NULL,fencing_token=fencing_token+1,
                    revision=revision+1,updated_at=clock_timestamp(),completed_at=clock_timestamp()
                 WHERE owner_id=? AND state IN('PENDING','ACTIVE','READY_TO_FINALIZE','FINALIZING')
                """, userId);
        jdbc.update("""
                UPDATE platform_project_coding_jobs SET state='BLOCKED',
                    safe_error_code='PROJECT_CODING_CLEANUP_QUIESCE',claim_owner=NULL,
                    claim_token=NULL,lease_until=NULL,fencing_token=fencing_token+1,
                    revision=revision+1,updated_at=clock_timestamp(),completed_at=clock_timestamp()
                 WHERE owner_id=? AND state IN('PENDING','RUNNING','WAITING_APPROVAL')
                """, userId);
        jdbc.update("""
                UPDATE platform_project_plan_executions SET state='BLOCKED',
                    safe_error_code='PROJECT_PLAN_EXECUTION_CLEANUP_QUIESCE',
                    revision=revision+1,updated_at=clock_timestamp(),completed_at=clock_timestamp()
                 WHERE owner_id=? AND state IN('READY','RUNNING','PAUSING','PAUSED','CANCELLING')
                """, userId);
        jdbc.update("""
                UPDATE platform_runtime_continuations continuation
                SET state = 'CANCELLED', claim_token = NULL, claim_owner = NULL,
                    fencing_token = NULL, lease_until = NULL,
                    last_error = 'User cleanup quiesce', revision = revision + 1,
                    updated_at = clock_timestamp(), completed_at = clock_timestamp()
                WHERE continuation.agent_run_id IN (
                    SELECT id FROM platform_agent_runs WHERE owner_id = ?)
                  AND continuation.state IN ('PENDING', 'CLAIMED')
                """, userId);
        jdbc.update("""
                UPDATE platform_agent_runs SET state = 'CANCELLED',
                    failure_reason = 'User cleanup quiesce', revision = revision + 1,
                    updated_at = clock_timestamp(), completed_at = COALESCE(completed_at, clock_timestamp())
                WHERE owner_id = ? AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """, userId);
        jdbc.update("""
                UPDATE platform_run_worker_leases lease
                SET released_at = COALESCE(released_at, clock_timestamp()),
                    revision = revision + 1, heartbeat_at = clock_timestamp()
                WHERE lease.agent_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = ?)
                  AND lease.released_at IS NULL
                """, userId);
        Instant maximumLease = max(max(window == null ? null : window.maxLeaseUntil(), codingLease), handoffLease);
        if (window != null && maximumLease != null && maximumLease.isAfter(window.databaseNow())) {
            return new QuiesceView(false, maximumLease.plusSeconds(1));
        }
        return new QuiesceView(true, null);
    }

    @Override @Transactional(readOnly = true)
    public List<String> userRunIds(String userId) {
        return jdbc.queryForList(
                "SELECT id FROM platform_agent_runs WHERE owner_id = ? ORDER BY created_at, id",
                String.class, userId);
    }

    @Override @Transactional
    public void purgeUser(String userId) {
        jdbc.update("DELETE FROM platform_project_run_handoffs WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_project_coding_jobs WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_project_plan_step_assignments WHERE owner_id = ?", userId);
        jdbc.update("DELETE FROM platform_project_plan_executions WHERE owner_id = ?", userId);
        jdbc.update("""
                DELETE FROM platform_agent_reviews review
                WHERE review.parent_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = ?)
                   OR review.child_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = ?)
                """, userId, userId);
        jdbc.update("DELETE FROM platform_agent_delegations WHERE parent_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = ?) OR child_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = ?)", userId, userId);
        jdbc.update("DELETE FROM platform_agent_runs WHERE owner_id = ?", userId);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Instant max(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private record LeaseWindow(Instant databaseNow, Instant maxLeaseUntil) {
    }
}
