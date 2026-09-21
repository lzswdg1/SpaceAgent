package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.domain.RuntimeSystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresRuntimeSystemAdministrationQuery implements RuntimeSystemAdministrationQuery {
    private final JdbcTemplate jdbc;
    public PostgresRuntimeSystemAdministrationQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public OverviewRow overview() {
        return jdbc.queryForObject("""
                SELECT count(*) runs,
                       count(*) FILTER (WHERE state IN (
                         'QUEUED','IN_PROGRESS','WAITING_FOR_TOOL','WAITING_FOR_USER','CHECKPOINTING')) active_runs,
                       count(*) FILTER (WHERE state = 'COMPLETED') completed_runs,
                       count(*) FILTER (WHERE state = 'FAILED') failed_runs,
                       count(*) FILTER (WHERE state = 'CANCELLED') cancelled_runs,
                       count(*) FILTER (WHERE state = 'RECOVERING') recovering_runs,
                       NULL::BIGINT unknown_runs
                FROM platform_agent_runs
                """, (rs, row) -> new OverviewRow(rs.getLong("runs"), rs.getLong("active_runs"),
                rs.getLong("completed_runs"), rs.getLong("failed_runs"),
                rs.getLong("cancelled_runs"), rs.getLong("recovering_runs"),
                rs.getObject("unknown_runs", Long.class)));
    }
    @Override public PageRows<ResourceRow> runsByOwner(String userId, int offset, int limit) {
        var items = jdbc.query("""
                SELECT run.id, run.tenant_id, run.project_id parent_id, NULL::text name,
                       run.state status, run.agent_id relation,
                       run.created_at, run.updated_at,
                       CASE WHEN run.failure_reason ~ '^[A-Z0-9_]{1,160}$'
                         THEN run.failure_reason END safe_error_code,
                       (SELECT count(*) FROM platform_run_steps step
                         WHERE step.agent_run_id = run.id) primary_count,
                       (SELECT count(*) FROM platform_run_checkpoints checkpoint
                         WHERE checkpoint.agent_run_id = run.id) secondary_count
                FROM platform_agent_runs run WHERE run.owner_id = ?
                ORDER BY run.updated_at DESC, run.id LIMIT ? OFFSET ?
                """, (rs, row) -> new ResourceRow(rs.getString("id"),
                rs.getString("tenant_id"), rs.getString("parent_id"), rs.getString("name"),
                rs.getString("status"), rs.getString("relation"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("safe_error_code"),
                rs.getLong("primary_count"), rs.getLong("secondary_count")), userId, limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform_agent_runs WHERE owner_id = ?", Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }
    @Override public DeletionEvidenceRow deletionEvidence(String userId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE state IN (
                         'QUEUED','IN_PROGRESS','WAITING_FOR_TOOL','WAITING_FOR_USER','CHECKPOINTING')) active_runs,
                       count(*) FILTER (WHERE state = 'RECOVERING') recovering_runs
                FROM platform_agent_runs WHERE owner_id = ?
                """, (rs, row) -> new DeletionEvidenceRow(rs.getLong("active_runs"),
                rs.getLong("recovering_runs")), userId);
    }
}
