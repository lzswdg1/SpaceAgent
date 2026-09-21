package com.spaceagent.platform.observability.infrastructure.persistence;

import com.spaceagent.platform.observability.domain.ObservabilityOverview;
import com.spaceagent.platform.observability.domain.ObservabilityQueryRepository;
import com.spaceagent.platform.observability.domain.ObservabilityRealtime;
import com.spaceagent.platform.observability.domain.ObservabilitySession;
import com.spaceagent.platform.observability.domain.ObservabilitySessionPage;
import com.spaceagent.platform.observability.domain.ObservabilityTimeseriesPoint;
import com.spaceagent.platform.observability.domain.ObservabilityUsageRow;
import com.spaceagent.platform.observability.domain.UsageDimension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresObservabilityQueryRepository implements ObservabilityQueryRepository {

    private final JdbcTemplate jdbc;

    public PostgresObservabilityQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ObservabilityOverview overview(String tenantId, Instant since, String bucketSize) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM platform_observability_organizations
                      WHERE id = ?) AS total_organizations,
                    (SELECT COUNT(*) FROM platform_observability_agents
                      WHERE tenant_id = ? AND status = 'ACTIVE') AS total_agents,
                    COUNT(DISTINCT run.conversation_id) FILTER
                      (WHERE run.created_at >= COALESCE(?, '-infinity'::timestamptz)) AS total_sessions,
                    COUNT(DISTINCT run.conversation_id) FILTER
                      (WHERE run.state NOT IN ('COMPLETED','FAILED','CANCELLED')
                         AND run.state NOT IN ('WAITING_FOR_TOOL','WAITING_FOR_USER'))
                      AS active_sessions,
                    COUNT(DISTINCT run.conversation_id) FILTER
                      (WHERE run.state IN ('WAITING_FOR_TOOL','WAITING_FOR_USER'))
                      AS idle_sessions,
                    COUNT(DISTINCT run.conversation_id) FILTER
                      (WHERE run.state IN ('COMPLETED','FAILED','CANCELLED')
                         AND run.created_at >= COALESCE(?, '-infinity'::timestamptz)) AS closed_sessions,
                    COUNT(*) FILTER
                      (WHERE run.state NOT IN ('COMPLETED','FAILED','CANCELLED'))
                      AS runtime_active,
                    (SELECT COALESCE(SUM(call.input_tokens),0)
                       FROM platform_observability_model_calls call
                      WHERE call.tenant_id = ? AND call.created_at >= COALESCE(?, '-infinity'::timestamptz)) AS input_tokens,
                    (SELECT COALESCE(SUM(call.output_tokens),0)
                       FROM platform_observability_model_calls call
                      WHERE call.tenant_id = ? AND call.created_at >= COALESCE(?, '-infinity'::timestamptz)) AS output_tokens,
                    (SELECT COALESCE(SUM(call.cache_read_tokens),0)
                       FROM platform_observability_model_calls call
                      WHERE call.tenant_id = ? AND call.created_at >= COALESCE(?, '-infinity'::timestamptz)) AS cache_read_tokens,
                    (SELECT COALESCE(SUM(call.cache_create_tokens),0)
                       FROM platform_observability_model_calls call
                      WHERE call.tenant_id = ? AND call.created_at >= COALESCE(?, '-infinity'::timestamptz)) AS cache_create_tokens,
                    (SELECT SUM(call.cost_micros) FILTER (WHERE call.status = 'SUCCEEDED')
                       FROM platform_observability_model_calls call
                      WHERE call.tenant_id = ? AND call.created_at >= COALESCE(?, '-infinity'::timestamptz)) AS cost_micros,
                    (SELECT COALESCE(MAX(bucket_count),0) FROM (
                        SELECT COUNT(DISTINCT bucket_run.conversation_id) AS bucket_count
                        FROM platform_observability_runs bucket_run
                        WHERE bucket_run.tenant_id = ? AND bucket_run.created_at >= COALESCE(?, '-infinity'::timestamptz)
                        GROUP BY date_trunc(?, bucket_run.created_at)
                    ) buckets) AS peak_concurrent,
                    (SELECT COUNT(*) FILTER (
                            WHERE call.status IN ('SUCCEEDED','UNKNOWN') AND call.cost_micros IS NULL) > 0
                       FROM platform_observability_model_calls call
                      WHERE call.tenant_id = ? AND call.created_at >= COALESCE(?, '-infinity'::timestamptz)) AS incomplete_cost
                FROM platform_observability_runs run
                WHERE run.tenant_id = ?
                """, (rs, row) -> new ObservabilityOverview(
                        rs.getLong("total_organizations"), rs.getLong("total_agents"),
                        rs.getLong("total_sessions"), rs.getLong("active_sessions"),
                        rs.getLong("idle_sessions"), rs.getLong("closed_sessions"),
                        rs.getLong("runtime_active"), rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"), rs.getLong("cache_read_tokens"),
                        rs.getLong("cache_create_tokens"), nullableLong(rs, "cost_micros"),
                        rs.getLong("peak_concurrent"), rs.getBoolean("incomplete_cost")),
                tenantId, tenantId, timestamp(since), timestamp(since),
                tenantId, timestamp(since), tenantId, timestamp(since),
                tenantId, timestamp(since), tenantId, timestamp(since),
                tenantId, timestamp(since), tenantId, timestamp(since), bucketSize,
                tenantId, timestamp(since), tenantId);
    }

    @Override
    public List<ObservabilityTimeseriesPoint> timeseries(
            String tenantId,
            Instant since,
            String bucketSize) {
        return jdbc.query("""
                WITH model AS (
                    SELECT date_trunc(?, created_at) AS bucket,
                           SUM(input_tokens) AS input_tokens,
                           SUM(output_tokens) AS output_tokens,
                           SUM(cache_read_tokens) AS cache_read_tokens,
                           SUM(cache_create_tokens) AS cache_create_tokens,
                           SUM(cost_micros) FILTER (WHERE status = 'SUCCEEDED') AS cost_micros,
                           COUNT(*) AS calls
                    FROM platform_observability_model_calls
                    WHERE tenant_id = ? AND created_at >= COALESCE(?, '-infinity'::timestamptz)
                    GROUP BY bucket
                ), sessions AS (
                    SELECT date_trunc(?, created_at) AS bucket,
                           COUNT(DISTINCT conversation_id) AS active_sessions
                    FROM platform_observability_runs
                    WHERE tenant_id = ? AND created_at >= COALESCE(?, '-infinity'::timestamptz)
                    GROUP BY bucket
                )
                SELECT COALESCE(model.bucket, sessions.bucket) AS bucket,
                       COALESCE(model.input_tokens,0) AS input_tokens,
                       COALESCE(model.output_tokens,0) AS output_tokens,
                       COALESCE(model.cache_read_tokens,0) AS cache_read_tokens,
                       COALESCE(model.cache_create_tokens,0) AS cache_create_tokens,
                       model.cost_micros AS cost_micros,
                       COALESCE(model.calls,0) AS calls,
                       COALESCE(sessions.active_sessions,0) AS active_sessions
                FROM model FULL OUTER JOIN sessions ON sessions.bucket = model.bucket
                ORDER BY bucket
                """, this::mapPoint,
                bucketSize, tenantId, timestamp(since),
                bucketSize, tenantId, timestamp(since));
    }

    @Override
    public List<ObservabilityUsageRow> usage(
            String tenantId,
            Instant since,
            UsageDimension dimension,
            int limit) {
        Grouping grouping = grouping(dimension);
        String sql = """
                SELECT %s AS group_key,
                       %s AS group_name,
                       %s AS sub_label,
                       %s AS agent_count,
                       COUNT(DISTINCT call.conversation_id) AS session_count,
                       SUM(call.input_tokens) AS input_tokens,
                       SUM(call.output_tokens) AS output_tokens,
                       SUM(call.cache_read_tokens) AS cache_read_tokens,
                       SUM(call.cache_create_tokens) AS cache_create_tokens,
                       SUM(call.cost_micros) FILTER (WHERE call.status = 'SUCCEEDED') AS cost_micros,
                       COUNT(*) AS call_count,
                       AVG(call.latency_ms) AS avg_latency_ms,
                       COUNT(*) FILTER (WHERE call.status IN ('SUCCEEDED','UNKNOWN')
                           AND call.cost_micros IS NULL) > 0 AS incomplete_cost
                FROM platform_observability_model_calls call
                LEFT JOIN platform_observability_organizations organization
                  ON organization.id = call.tenant_id
                WHERE call.tenant_id = ? AND call.created_at >= COALESCE(?, '-infinity'::timestamptz)
                GROUP BY %s, %s, %s
                ORDER BY %s, group_key
                LIMIT ?
                """.formatted(
                grouping.key(), grouping.name(), grouping.subLabel(), grouping.agentCount(),
                grouping.key(), grouping.name(), grouping.subLabel(),
                dimension == UsageDimension.AGENT
                        ? "(SUM(call.input_tokens) + SUM(call.output_tokens) + SUM(call.cache_read_tokens) + SUM(call.cache_create_tokens)) DESC"
                        : "call_count DESC");
        return jdbc.query(sql, this::mapUsage, tenantId, timestamp(since), limit);
    }

    @Override
    public ObservabilityRealtime realtime(String tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER
                         (WHERE run.state NOT IN ('COMPLETED','FAILED','CANCELLED'))
                         AS runtime_active,
                       COUNT(*) FILTER
                         (WHERE run.state IN ('IN_PROGRESS','CHECKPOINTING','RECOVERING'))
                         AS processing,
                       organization.id AS organization_id,
                       organization.name AS organization_name
                FROM platform_observability_organizations organization
                LEFT JOIN platform_observability_runs run ON run.tenant_id = organization.id
                WHERE organization.id = ?
                GROUP BY organization.id, organization.name
                """, (rs, row) -> new ObservabilityRealtime(
                        rs.getLong("runtime_active"), rs.getLong("processing"),
                        rs.getString("organization_id"), rs.getString("organization_name")),
                tenantId);
    }

    @Override
    public ObservabilitySessionPage sessions(
            String tenantId,
            Instant since,
            String status,
            int offset,
            int limit) {
        String condition = statusCondition(status);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_observability_runs run
                WHERE run.tenant_id = ? AND run.created_at >= COALESCE(?, '-infinity'::timestamptz)
                """ + condition, Long.class, tenantId, timestamp(since));
        Long active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_observability_runs
                WHERE tenant_id = ? AND state NOT IN ('COMPLETED','FAILED','CANCELLED')
                """, Long.class, tenantId);
        List<ObservabilitySession> values = jdbc.query("""
                SELECT run.* FROM platform_observability_runs run
                WHERE run.tenant_id = ? AND run.created_at >= COALESCE(?, '-infinity'::timestamptz)
                """ + condition + " ORDER BY run.updated_at DESC, run.id LIMIT ? OFFSET ?",
                this::mapSession, tenantId, timestamp(since), limit, offset);
        return new ObservabilitySessionPage(
                values, total == null ? 0 : total, active == null ? 0 : active);
    }

    @Override
    public Optional<ObservabilitySession> findSession(String tenantId, String agentRunId) {
        return jdbc.query("""
                SELECT run.* FROM platform_observability_runs run
                WHERE run.tenant_id = ? AND run.id = ?
                """, this::mapSession, tenantId, agentRunId).stream().findFirst();
    }

    private ObservabilityTimeseriesPoint mapPoint(ResultSet rs, int row) throws SQLException {
        return new ObservabilityTimeseriesPoint(
                rs.getTimestamp("bucket").toInstant(), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getLong("cache_read_tokens"),
                rs.getLong("cache_create_tokens"), nullableLong(rs, "cost_micros"),
                rs.getLong("calls"), rs.getLong("active_sessions"));
    }

    private ObservabilityUsageRow mapUsage(ResultSet rs, int row) throws SQLException {
        return new ObservabilityUsageRow(
                rs.getString("group_key"), rs.getString("group_name"),
                rs.getString("sub_label"), nullableLong(rs, "agent_count"),
                rs.getLong("session_count"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getLong("cache_read_tokens"),
                rs.getLong("cache_create_tokens"), nullableLong(rs, "cost_micros"),
                rs.getLong("call_count"), rs.getDouble("avg_latency_ms"),
                rs.getBoolean("incomplete_cost"));
    }

    private ObservabilitySession mapSession(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        boolean terminal = List.of("COMPLETED", "FAILED", "CANCELLED").contains(state);
        String status = terminal
                ? "closed"
                : List.of("WAITING_FOR_TOOL", "WAITING_FOR_USER").contains(state)
                        ? "idle" : "active";
        return new ObservabilitySession(
                rs.getString("id"), rs.getString("conversation_name"),
                rs.getString("agent_id"), rs.getString("agent_name"),
                rs.getString("tenant_id"), rs.getString("organization_name"),
                rs.getString("provider_name"), !terminal,
                rs.getLong("input_tokens"), rs.getLong("output_tokens"), status,
                rs.getLong("duration_seconds"), instant(rs.getTimestamp("updated_at")),
                rs.getLong("message_count"), state.equals("IN_PROGRESS"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("completed_at")), rs.getString("failure_reason"),
                rs.getString("source"));
    }

    private static Grouping grouping(UsageDimension dimension) {
        return switch (dimension) {
            case ORGANIZATION -> new Grouping(
                    "call.tenant_id", "COALESCE(organization.name, call.tenant_id)",
                    "NULL::TEXT", "(SELECT COUNT(*) FROM platform_observability_agents agent "
                            + "WHERE agent.tenant_id = call.tenant_id AND agent.status='ACTIVE')");
            case AGENT -> new Grouping(
                    "call.agent_id", "COALESCE(call.agent_name, call.agent_id)",
                    "NULL::TEXT", "NULL::BIGINT");
            case MODEL -> new Grouping(
                    "call.provider_id || ':' || call.model_id", "call.model_id",
                    "call.provider_name", "NULL::BIGINT");
            case PROVIDER -> new Grouping(
                    "call.provider_id", "call.provider_name", "NULL::TEXT", "NULL::BIGINT");
        };
    }

    private static String statusCondition(String status) {
        return switch (status) {
            case "active" -> " AND run.state NOT IN ('COMPLETED','FAILED','CANCELLED',"
                    + "'WAITING_FOR_TOOL','WAITING_FOR_USER')";
            case "idle" -> " AND run.state IN ('WAITING_FOR_TOOL','WAITING_FOR_USER')";
            case "closed" -> " AND run.state IN ('COMPLETED','FAILED','CANCELLED')";
            default -> "";
        };
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private record Grouping(String key, String name, String subLabel, String agentCount) {}
}
