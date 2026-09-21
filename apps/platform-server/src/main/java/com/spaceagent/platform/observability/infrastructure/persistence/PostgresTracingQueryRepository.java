package com.spaceagent.platform.observability.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.observability.domain.TraceDetail;
import com.spaceagent.platform.observability.domain.TraceFilter;
import com.spaceagent.platform.observability.domain.TracePage;
import com.spaceagent.platform.observability.domain.TraceSpan;
import com.spaceagent.platform.observability.domain.TraceSpanType;
import com.spaceagent.platform.observability.domain.TraceStats;
import com.spaceagent.platform.observability.domain.TraceStatus;
import com.spaceagent.platform.observability.domain.TraceSummary;
import com.spaceagent.platform.observability.domain.TracingQueryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresTracingQueryRepository implements TracingQueryRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String SUMMARY_COLUMNS = """
            trace_id, agent_run_id, tenant_id, owner_id, agent_id, agent_name,
            session_id, session_name, project_id, task_id, run_state,
            error_message, start_time, end_time, duration_ms, first_token_ms, llm_ms,
            tool_wall_ms, tool_duration_sum_ms, span_count, llm_turns, tool_calls,
            input_tokens, output_tokens, total_tokens, cache_create_tokens,
            cache_read_tokens, cost_usd, cost_estimated, metadata::text AS metadata,
            created_at, updated_at, trace_status
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresTracingQueryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public TracePage search(TraceFilter filter, int limit, int offset) {
        SqlFilter sql = where(filter);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_trace_summaries summary " + sql.clause(),
                Long.class, sql.arguments().toArray());
        List<Object> arguments = new ArrayList<>(sql.arguments());
        arguments.add(limit);
        arguments.add(offset);
        List<TraceSummary> traces = jdbc.query(
                "SELECT " + SUMMARY_COLUMNS + " FROM platform_trace_summaries summary "
                        + sql.clause() + " ORDER BY start_time DESC, trace_id LIMIT ? OFFSET ?",
                this::mapSummary, arguments.toArray());
        return new TracePage(traces, total == null ? 0 : total, limit, offset);
    }

    @Override
    public Optional<TraceDetail> find(String tenantId, String ownerId, String traceId) {
        Optional<TraceSummary> summary = jdbc.query(
                "SELECT " + SUMMARY_COLUMNS + " FROM platform_trace_summaries "
                        + "WHERE tenant_id = ? AND owner_id = ? AND trace_id = ?",
                this::mapSummary, tenantId, ownerId, traceId).stream().findFirst();
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        TraceSummary trace = summary.get();
        String agentRunId = String.valueOf(trace.metadata().get("agentRunId"));
        List<TraceSpan> spans = new ArrayList<>();
        spans.add(rootSpan(trace));
        spans.addAll(jdbc.query("""
                SELECT source_id, trace_id, span_type, name, trace_status, error_message,
                       start_time, end_time, duration_ms, model, input_tokens, output_tokens,
                       cache_create_tokens, cache_read_tokens, metadata::text AS metadata,
                       created_at, updated_at
                FROM platform_trace_spans
                WHERE trace_id = ?
                ORDER BY start_time, created_at, source_id
                """, (row, number) -> mapSpan(row, trace), agentRunId));
        return Optional.of(new TraceDetail(trace, spans));
    }

    @Override
    public TraceStats stats(TraceFilter filter) {
        SqlFilter sql = where(filter);
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total_traces,
                       COUNT(*) FILTER (WHERE trace_status = 'SUCCESS') AS success_traces,
                       COUNT(*) FILTER (WHERE trace_status = 'ERROR') AS error_traces,
                       COUNT(*) FILTER (WHERE trace_status IN ('ABORTED','RETRY_ABORTED'))
                           AS aborted_traces,
                       COALESCE(AVG(duration_ms),0) AS avg_duration_ms,
                       COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms)
                           FILTER (WHERE duration_ms IS NOT NULL),0) AS p50_duration_ms,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
                           FILTER (WHERE duration_ms IS NOT NULL),0) AS p95_duration_ms,
                       COALESCE(AVG(first_token_ms)
                           FILTER (WHERE first_token_ms IS NOT NULL),0) AS avg_first_token_ms,
                       COALESCE(SUM(total_tokens),0) AS total_tokens,
                       NULL::NUMERIC AS total_cost_usd
                FROM platform_trace_summaries summary
                """ + sql.clause(), this::mapStats, sql.arguments().toArray());
    }

    private SqlFilter where(TraceFilter filter) {
        StringBuilder clause = new StringBuilder("WHERE summary.tenant_id = ? AND summary.owner_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(filter.tenantId());
        arguments.add(filter.ownerId());
        if (filter.status() != null) {
            clause.append(" AND summary.trace_status = ?");
            arguments.add(filter.status().name());
        }
        if (filter.agentId() != null) {
            clause.append(" AND summary.agent_id = ?");
            arguments.add(filter.agentId());
        }
        if (filter.sessionId() != null) {
            clause.append(" AND summary.session_id = ?");
            arguments.add(filter.sessionId());
        }
        if (filter.keyword() != null) {
            clause.append(" AND (summary.trace_id ILIKE ? OR summary.agent_name ILIKE ?")
                    .append(" OR summary.session_name ILIKE ? OR summary.error_message ILIKE ?)");
            String pattern = "%" + filter.keyword() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (filter.minDurationMs() != null) {
            clause.append(" AND COALESCE(summary.duration_ms,0) >= ?");
            arguments.add(filter.minDurationMs());
        }
        if (filter.maxDurationMs() != null) {
            clause.append(" AND COALESCE(summary.duration_ms,0) <= ?");
            arguments.add(filter.maxDurationMs());
        }
        if (filter.since() != null) {
            clause.append(" AND summary.start_time >= ?");
            arguments.add(Timestamp.from(filter.since()));
        }
        if (filter.until() != null) {
            clause.append(" AND summary.start_time <= ?");
            arguments.add(Timestamp.from(filter.until()));
        }
        return new SqlFilter(clause.toString(), arguments);
    }

    private TraceSummary mapSummary(ResultSet row, int number) throws SQLException {
        long input = row.getLong("input_tokens");
        long output = row.getLong("output_tokens");
        return new TraceSummary(
                row.getString("trace_id"), row.getString("tenant_id"),
                row.getString("session_id"), row.getString("session_name"),
                row.getString("agent_id"), row.getString("agent_name"),
                row.getString("owner_id"), rootSpanId(row.getString("trace_id")),
                TraceStatus.valueOf(row.getString("trace_status")),
                row.getString("trace_status").equals("ERROR")
                        || row.getString("trace_status").equals("RETRY_ABORTED"),
                row.getString("error_message"), instant(row.getTimestamp("start_time")),
                instant(row.getTimestamp("end_time")), nullableLong(row, "duration_ms"),
                nullableLong(row, "first_token_ms"), row.getLong("llm_ms"),
                row.getLong("tool_wall_ms"), row.getLong("tool_duration_sum_ms"),
                row.getInt("span_count"), row.getInt("llm_turns"), row.getInt("tool_calls"),
                input, output, row.getLong("total_tokens"),
                row.getLong("cache_create_tokens"), row.getLong("cache_read_tokens"),
                nullableDouble(row, "cost_usd"), row.getBoolean("cost_estimated"),
                metadata(row.getString("metadata")), instant(row.getTimestamp("created_at")),
                instant(row.getTimestamp("updated_at")));
    }

    private TraceSpan mapSpan(ResultSet row, TraceSummary trace) throws SQLException {
        TraceSpanType type = TraceSpanType.valueOf(row.getString("span_type"));
        Long input = nullableLong(row, "input_tokens");
        Long output = nullableLong(row, "output_tokens");
        Long cacheCreate = nullableLong(row, "cache_create_tokens");
        Long cacheRead = nullableLong(row, "cache_read_tokens");
        Long total = input == null && output == null && cacheCreate == null && cacheRead == null
                ? null : value(input) + value(output) + value(cacheCreate) + value(cacheRead);
        TraceStatus status = TraceStatus.valueOf(row.getString("trace_status"));
        return new TraceSpan(
                stableSpanId(trace.id(), type, row.getString("source_id")), trace.id(),
                trace.rootSpanId(), type, row.getString("name"), status,
                status == TraceStatus.ERROR || status == TraceStatus.RETRY_ABORTED,
                row.getString("error_message"), instant(row.getTimestamp("start_time")),
                instant(row.getTimestamp("end_time")), nullableLong(row, "duration_ms"),
                row.getString("model"), input, output, total, cacheCreate, cacheRead,
                null, null, metadata(row.getString("metadata")),
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")));
    }

    private TraceSpan rootSpan(TraceSummary trace) {
        return new TraceSpan(
                trace.rootSpanId(), trace.id(), null, TraceSpanType.ROOT,
                "agent-run", trace.status(), trace.error(), trace.errorMessage(),
                trace.startTime(), trace.endTime(), trace.durationMs(), null,
                trace.inputTokens(), trace.outputTokens(), trace.totalTokens(),
                trace.cacheCreateTokens(), trace.cacheReadTokens(), null, null,
                trace.metadata(), trace.createdAt(), trace.updatedAt());
    }

    private TraceStats mapStats(ResultSet row, int number) throws SQLException {
        return new TraceStats(
                row.getLong("total_traces"), row.getLong("success_traces"),
                row.getLong("error_traces"), row.getLong("aborted_traces"),
                row.getDouble("avg_duration_ms"), row.getDouble("p50_duration_ms"),
                row.getDouble("p95_duration_ms"), row.getDouble("avg_first_token_ms"),
                row.getLong("total_tokens"), nullableDouble(row, "total_cost_usd"));
    }

    private Map<String, Object> metadata(String value) {
        try {
            return value == null ? Map.of() : json.readValue(value, MAP);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to decode redacted Trace metadata", error);
        }
    }

    private static String rootSpanId(String traceId) {
        return stableUuid("trace-root:" + traceId).toString();
    }

    private static String stableSpanId(String traceId, TraceSpanType type, String sourceId) {
        return stableUuid("trace-span:" + traceId + ":" + type.name() + ":" + sourceId)
                .toString();
    }

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet row, String column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record SqlFilter(String clause, List<Object> arguments) {}
}
