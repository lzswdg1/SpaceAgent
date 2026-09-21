package com.spaceagent.platform.observability.infrastructure.persistence;

import com.spaceagent.platform.observability.domain.AgentOperationalMetricsQueryRepository;
import com.spaceagent.platform.observability.domain.AgentOperationalMetricsSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** PostgreSQL adapter restricted to the disposable {@code platform_trace_*} projections. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAgentOperationalMetricsQueryRepository
        implements AgentOperationalMetricsQueryRepository {

    private final JdbcTemplate jdbc;

    public PostgresAgentOperationalMetricsQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AgentOperationalMetricsSnapshot snapshot(Instant since, Instant observedAt) {
        return jdbc.queryForObject("""
                WITH bounds AS (
                    SELECT CAST(? AS TIMESTAMP WITH TIME ZONE) AS since
                ), recent_runs AS (
                    SELECT run.*
                    FROM platform_trace_summaries run, bounds
                    WHERE run.start_time >= bounds.since
                ), current_runs AS (
                    SELECT run_state
                    FROM platform_trace_summaries
                    WHERE run_state NOT IN ('COMPLETED','FAILED','CANCELLED')
                ), recent_spans AS (
                    SELECT span.*
                    FROM platform_trace_spans span, bounds
                    WHERE span.start_time >= bounds.since
                )
                SELECT
                    (SELECT COUNT(*) FROM current_runs) AS active_runs,
                    (SELECT COUNT(*) FROM current_runs WHERE run_state = 'RECOVERING')
                        AS recovering_runs,
                    (SELECT COUNT(*) FROM recent_runs) AS run_total,
                    (SELECT COUNT(*) FROM recent_runs WHERE trace_status = 'SUCCESS')
                        AS run_success,
                    (SELECT COUNT(*) FROM recent_runs WHERE trace_status = 'ERROR') AS run_error,
                    (SELECT COUNT(*) FROM recent_runs WHERE trace_status = 'ABORTED')
                        AS run_aborted,
                    (SELECT COUNT(*) FROM recent_runs WHERE trace_status = 'RETRY_ABORTED')
                        AS run_unknown,
                    (SELECT COUNT(*) FROM recent_runs WHERE trace_status = 'RUNNING')
                        AS run_running,
                    (SELECT COUNT(*) FROM recent_spans WHERE span_type = 'LLM') AS model_total,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'LLM' AND trace_status = 'SUCCESS') AS model_success,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'LLM' AND trace_status = 'ERROR') AS model_error,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'LLM' AND trace_status = 'ABORTED') AS model_aborted,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'LLM' AND trace_status = 'RETRY_ABORTED') AS model_unknown,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'LLM' AND trace_status = 'RUNNING') AS model_running,
                    (SELECT COUNT(*) FROM recent_spans WHERE span_type = 'TOOL') AS tool_total,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'TOOL' AND trace_status = 'SUCCESS') AS tool_success,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'TOOL' AND trace_status = 'ERROR') AS tool_error,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'TOOL' AND trace_status = 'ABORTED') AS tool_aborted,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'TOOL' AND trace_status = 'RETRY_ABORTED') AS tool_unknown,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'TOOL' AND trace_status = 'RUNNING') AS tool_running,
                    COALESCE((SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
                      FROM recent_runs WHERE duration_ms IS NOT NULL), 0) AS run_p95_ms,
                    COALESCE((SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
                      FROM recent_spans WHERE span_type = 'LLM' AND duration_ms IS NOT NULL), 0)
                        AS model_p95_ms,
                    COALESCE((SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
                      FROM recent_spans WHERE span_type = 'TOOL' AND duration_ms IS NOT NULL), 0)
                        AS tool_p95_ms,
                    COALESCE((SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY first_token_ms)
                      FROM recent_runs WHERE first_token_ms IS NOT NULL), 0)
                        AS first_chunk_p95_ms,
                    COALESCE((SELECT SUM(input_tokens) FROM recent_runs), 0) AS input_tokens,
                    COALESCE((SELECT SUM(output_tokens) FROM recent_runs), 0) AS output_tokens,
                    COALESCE((SELECT SUM(cache_read_tokens) FROM recent_runs), 0)
                        AS cache_read_tokens,
                    COALESCE((SELECT SUM(cache_create_tokens) FROM recent_runs), 0)
                        AS cache_create_tokens,
                    COALESCE((SELECT ROUND(SUM(cost_usd) * 1000000)::BIGINT
                      FROM recent_runs WHERE cost_usd IS NOT NULL), 0) AS settled_cost_micros,
                    (SELECT COUNT(*) FROM recent_runs
                      WHERE llm_turns > 0 AND cost_usd IS NULL) AS incomplete_cost_runs,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'SYSTEM' AND name LIKE 'handoff:%') AS handoffs,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'SYSTEM' AND name LIKE 'handoff:%'
                        AND trace_status = 'ERROR') AS handoff_errors,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'SYSTEM' AND name LIKE 'review:%') AS reviews,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'SYSTEM' AND name = 'review:changes_requested')
                        AS review_changes_requested,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'SYSTEM' AND name = 'artifact:acceptance_evidence')
                        AS acceptance_evidence,
                    (SELECT COUNT(*) FROM recent_spans
                      WHERE span_type = 'SYSTEM' AND name = 'continuation_failed')
                        AS continuation_failures
                FROM bounds
                """, (rs, row) -> map(rs, observedAt), Timestamp.from(since));
    }

    private static AgentOperationalMetricsSnapshot map(ResultSet rs, Instant observedAt)
            throws SQLException {
        return new AgentOperationalMetricsSnapshot(
                observedAt,
                rs.getLong("active_runs"),
                rs.getLong("recovering_runs"),
                outcomes(rs, "run"),
                outcomes(rs, "model"),
                outcomes(rs, "tool"),
                new AgentOperationalMetricsSnapshot.LatencyP95(
                        rs.getDouble("run_p95_ms"),
                        rs.getDouble("model_p95_ms"),
                        rs.getDouble("tool_p95_ms"),
                        rs.getDouble("first_chunk_p95_ms")),
                new AgentOperationalMetricsSnapshot.TokenUsage(
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("cache_read_tokens"),
                        rs.getLong("cache_create_tokens")),
                new AgentOperationalMetricsSnapshot.CostEvidence(
                        rs.getLong("settled_cost_micros"),
                        rs.getLong("incomplete_cost_runs")),
                new AgentOperationalMetricsSnapshot.CollaborationEvidence(
                        rs.getLong("handoffs"),
                        rs.getLong("handoff_errors"),
                        rs.getLong("reviews"),
                        rs.getLong("review_changes_requested"),
                        rs.getLong("acceptance_evidence"),
                        rs.getLong("continuation_failures")));
    }

    private static AgentOperationalMetricsSnapshot.OutcomeCounts outcomes(
            ResultSet rs,
            String prefix) throws SQLException {
        return new AgentOperationalMetricsSnapshot.OutcomeCounts(
                rs.getLong(prefix + "_total"),
                rs.getLong(prefix + "_success"),
                rs.getLong(prefix + "_error"),
                rs.getLong(prefix + "_aborted"),
                rs.getLong(prefix + "_unknown"),
                rs.getLong(prefix + "_running"));
    }
}
