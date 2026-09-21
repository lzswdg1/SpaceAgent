package com.spaceagent.platform.automation.infrastructure.persistence;

import com.spaceagent.platform.automation.domain.AutomationSystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAutomationSystemAdministrationQuery
        implements AutomationSystemAdministrationQuery {
    private final JdbcTemplate jdbc;

    public PostgresAutomationSystemAdministrationQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public PageRows<ResourceRow> schedulesByOwner(
            String userId, int offset, int limit) {
        var items = jdbc.query("""
                SELECT schedule.id::text id, schedule.tenant_id, schedule.agent_id,
                       schedule.state, schedule.schedule_type relation,
                       schedule.created_at, schedule.updated_at,
                       CASE WHEN schedule.last_error ~ '^[A-Z0-9_]{1,160}$'
                         THEN schedule.last_error END safe_error_code,
                       (SELECT count(*) FROM platform_automation_executions execution
                         WHERE execution.schedule_id = schedule.id) execution_count,
                       (SELECT count(*) FROM platform_automation_executions execution
                         WHERE execution.schedule_id = schedule.id
                           AND execution.state IN ('FAILED','UNKNOWN')) risk_count
                FROM platform_automation_schedules schedule WHERE schedule.owner_id = ?
                ORDER BY schedule.updated_at DESC, schedule.id LIMIT ? OFFSET ?
                """, (rs, row) -> new ResourceRow(rs.getString("id"),
                rs.getString("tenant_id"), rs.getString("agent_id"), rs.getString("state"),
                rs.getString("relation"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("safe_error_code"),
                rs.getLong("execution_count"), rs.getLong("risk_count")), userId, limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform_automation_schedules WHERE owner_id = ?",
                Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }
}
