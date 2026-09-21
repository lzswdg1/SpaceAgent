package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.IdentityCleanupAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentityCleanupAdministrationQuery
        implements IdentityCleanupAdministrationQuery {
    private static final String JOBS = """
            WITH cleanup_jobs AS (
              SELECT 'USER' kind, job.user_id subject_id, job.state,
                     job.command_id::text command_id, job.requested_by::text requested_by,
                     job.retention_not_before, job.next_attempt_at, job.attempt, job.max_attempts,
                     job.last_error_code, job.last_error_summary, job.revision,
                     (SELECT count(*) FROM platform_user_cleanup_steps step
                       WHERE step.user_id = job.user_id AND step.state = 'COMPLETED') completed_steps,
                     (SELECT count(*) FROM platform_user_cleanup_steps step
                       WHERE step.user_id = job.user_id) total_steps,
                     (SELECT step.step_key FROM platform_user_cleanup_steps step
                       WHERE step.user_id = job.user_id AND step.state = 'PENDING'
                       ORDER BY step.step_sequence LIMIT 1) current_step_key,
                     job.created_at, job.updated_at, job.completed_at
                FROM platform_user_cleanup_jobs job
              UNION ALL
              SELECT 'ORGANIZATION' kind, job.organization_id subject_id, job.state,
                     NULL::text command_id, NULL::text requested_by,
                     job.retention_not_before, job.next_attempt_at, job.attempt, job.max_attempts,
                     job.last_error_code, job.last_error_summary, job.revision,
                     (SELECT count(*) FROM platform_organization_cleanup_steps step
                       WHERE step.organization_id = job.organization_id AND step.state = 'COMPLETED') completed_steps,
                     (SELECT count(*) FROM platform_organization_cleanup_steps step
                       WHERE step.organization_id = job.organization_id) total_steps,
                     (SELECT step.step_key FROM platform_organization_cleanup_steps step
                       WHERE step.organization_id = job.organization_id AND step.state = 'PENDING'
                       ORDER BY step.step_sequence LIMIT 1) current_step_key,
                     job.created_at, job.updated_at, job.completed_at
                FROM platform_organization_cleanup_jobs job
            )
            """;

    private final JdbcTemplate jdbc;

    public PostgresIdentityCleanupAdministrationQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public OverviewRow overview() {
        var counts = jdbc.queryForObject(JOBS + """
                SELECT count(*) FILTER (WHERE state = 'PENDING') pending,
                       count(*) FILTER (WHERE state = 'CLAIMED') claimed,
                       count(*) FILTER (WHERE state = 'RETRY') retry,
                       count(*) FILTER (WHERE state = 'BLOCKED') blocked,
                       count(*) FILTER (WHERE state = 'COMPLETED') completed
                FROM cleanup_jobs
                """, (rs, row) -> new long[]{rs.getLong("pending"), rs.getLong("claimed"),
                rs.getLong("retry"), rs.getLong("blocked"), rs.getLong("completed")});
        List<BlockerRow> blockers = jdbc.query(JOBS + """
                SELECT COALESCE(last_error_code, 'UNKNOWN_BLOCKER') code, count(*) total
                FROM cleanup_jobs WHERE state = 'BLOCKED'
                GROUP BY COALESCE(last_error_code, 'UNKNOWN_BLOCKER')
                ORDER BY total DESC, code LIMIT 20
                """, (rs, row) -> new BlockerRow(rs.getString("code"), rs.getLong("total")));
        return new OverviewRow(counts[0], counts[1], counts[2], counts[3], counts[4], blockers);
    }

    @Override
    public PageRows<JobRow> jobs(int offset, int limit, String kind, String state, String text) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> filters = new ArrayList<>();
        if (kind != null) { where.append(" AND kind = ? "); filters.add(kind); }
        if (state != null) { where.append(" AND state = ? "); filters.add(state); }
        if (text != null) { where.append(" AND subject_id = ? "); filters.add(text); }
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(limit); arguments.add(offset);
        List<JobRow> items = jdbc.query(JOBS + " SELECT * FROM cleanup_jobs " + where
                        + " ORDER BY created_at DESC, kind, subject_id LIMIT ? OFFSET ?",
                this::mapJob, arguments.toArray());
        Long total = jdbc.queryForObject(JOBS + " SELECT count(*) FROM cleanup_jobs " + where,
                Long.class, filters.toArray());
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public Optional<JobRow> job(String kind, String subjectId) {
        return jdbc.query(JOBS + " SELECT * FROM cleanup_jobs WHERE kind = ? AND subject_id = ?",
                this::mapJob, kind, subjectId).stream().findFirst();
    }

    @Override
    public List<StepRow> steps(String kind, String subjectId) {
        String table = "USER".equals(kind)
                ? "platform_user_cleanup_steps" : "platform_organization_cleanup_steps";
        String idColumn = "USER".equals(kind) ? "user_id" : "organization_id";
        return jdbc.query("SELECT step_key, step_sequence, state, attempt, last_error_code, "
                        + "last_error_summary, created_at, updated_at, completed_at FROM " + table
                        + " WHERE " + idColumn + " = ? ORDER BY step_sequence",
                this::mapStep, subjectId);
    }

    private JobRow mapJob(ResultSet rs, int row) throws SQLException {
        return new JobRow(rs.getString("kind"), rs.getString("subject_id"), rs.getString("state"),
                rs.getString("command_id"), rs.getString("requested_by"),
                instant(rs, "retention_not_before"), instant(rs, "next_attempt_at"),
                rs.getInt("attempt"), rs.getInt("max_attempts"), rs.getString("last_error_code"),
                rs.getString("last_error_summary"), rs.getLong("revision"),
                rs.getLong("completed_steps"), rs.getLong("total_steps"),
                rs.getString("current_step_key"), instant(rs, "created_at"),
                instant(rs, "updated_at"), nullableInstant(rs, "completed_at"));
    }

    private StepRow mapStep(ResultSet rs, int row) throws SQLException {
        return new StepRow(rs.getString("step_key"), rs.getInt("step_sequence"),
                rs.getString("state"), rs.getInt("attempt"), rs.getString("last_error_code"),
                rs.getString("last_error_summary"), instant(rs, "created_at"),
                instant(rs, "updated_at"), nullableInstant(rs, "completed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
