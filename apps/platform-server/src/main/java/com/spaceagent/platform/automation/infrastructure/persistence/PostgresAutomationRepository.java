package com.spaceagent.platform.automation.infrastructure.persistence;

import com.spaceagent.platform.automation.domain.AutomationExecution;
import com.spaceagent.platform.automation.domain.AutomationExecutionState;
import com.spaceagent.platform.automation.domain.AutomationRepository;
import com.spaceagent.platform.automation.domain.AutomationSchedule;
import com.spaceagent.platform.automation.domain.AutomationScheduleState;
import com.spaceagent.platform.automation.domain.AutomationScheduleType;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.automation.domain.DueAutomationSchedule;
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
public class PostgresAutomationRepository implements AutomationRepository {

    private static final String SCHEDULE_COLUMNS = """
            id, tenant_id, owner_id, agent_id, description, prompt, schedule_type,
            cron_expression, scheduled_at, timezone, state, next_fire_at, last_run_at,
            last_run_status, last_error, run_count, max_retries, revision,
            created_at, updated_at, archived_at
            """;
    private static final String EXECUTION_COLUMNS = """
            id, schedule_id, tenant_id, owner_id, agent_id, fire_key,
            scheduled_for, trigger_type, state, operation_hash, approval_id,
            conversation_id, dispatch_run_id, continuation_id, agent_run_id,
            started_at, completed_at, error, input_tokens, output_tokens, revision,
            created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public PostgresAutomationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant currentTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override
    public void createSchedule(AutomationSchedule schedule) {
        jdbc.update("""
                INSERT INTO platform_automation_schedules(
                    id, tenant_id, owner_id, agent_id, description, prompt, schedule_type,
                    cron_expression, scheduled_at, timezone, state, next_fire_at,
                    last_run_at, last_run_status, last_error, run_count, max_retries,
                    revision, created_at, updated_at, archived_at)
                VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                schedule.id(), schedule.tenantId(), schedule.ownerId(), schedule.agentId(),
                schedule.description(), schedule.prompt(), schedule.type().name(),
                schedule.cronExpression(), timestamp(schedule.scheduledAt()), schedule.timezone(),
                schedule.state().name(), timestamp(schedule.nextFireAt()),
                timestamp(schedule.lastRunAt()), schedule.lastRunStatus(), schedule.lastError(),
                schedule.runCount(), schedule.maxRetries(), schedule.revision(),
                timestamp(schedule.createdAt()), timestamp(schedule.updatedAt()),
                timestamp(schedule.archivedAt()));
    }

    @Override
    public Optional<AutomationSchedule> findSchedule(String scheduleId) {
        return jdbc.query("SELECT " + SCHEDULE_COLUMNS
                        + " FROM platform_automation_schedules WHERE id = CAST(? AS UUID)",
                this::mapSchedule, scheduleId).stream().findFirst();
    }

    @Override
    public List<AutomationSchedule> findSchedules(
            String tenantId, String ownerId, String agentId) {
        return jdbc.query("SELECT " + SCHEDULE_COLUMNS
                        + " FROM platform_automation_schedules "
                        + "WHERE tenant_id = ? AND owner_id = ? AND agent_id = ? "
                        + "AND state <> 'ARCHIVED' ORDER BY created_at, id",
                this::mapSchedule, tenantId, ownerId, agentId);
    }

    @Override
    public Optional<AutomationSchedule> updateSchedule(
            AutomationSchedule schedule, long expectedRevision) {
        return jdbc.query("""
                UPDATE platform_automation_schedules
                   SET description = ?, prompt = ?, cron_expression = ?, scheduled_at = ?,
                       timezone = ?, state = ?, next_fire_at = ?, last_run_at = ?,
                       last_run_status = ?, last_error = ?, run_count = ?, max_retries = ?,
                       revision = revision + 1, updated_at = ?, archived_at = ?
                 WHERE id = CAST(? AS UUID) AND revision = ?
                RETURNING """ + " " + SCHEDULE_COLUMNS,
                this::mapSchedule,
                schedule.description(), schedule.prompt(), schedule.cronExpression(),
                timestamp(schedule.scheduledAt()), schedule.timezone(), schedule.state().name(),
                timestamp(schedule.nextFireAt()), timestamp(schedule.lastRunAt()),
                schedule.lastRunStatus(), schedule.lastError(), schedule.runCount(),
                schedule.maxRetries(), timestamp(schedule.updatedAt()),
                timestamp(schedule.archivedAt()), schedule.id(), expectedRevision)
                .stream().findFirst();
    }

    @Override
    public Optional<DueAutomationSchedule> lockNextDueSchedule() {
        return jdbc.query("""
                SELECT schedule.*, clock_timestamp() AS database_now
                  FROM platform_automation_schedules schedule
                 WHERE schedule.state = 'ACTIVE'
                   AND schedule.next_fire_at <= clock_timestamp()
                 ORDER BY schedule.next_fire_at, schedule.created_at, schedule.id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """, (row, rowNumber) -> new DueAutomationSchedule(
                mapSchedule(row, rowNumber), instant(row.getTimestamp("database_now"))))
                .stream().findFirst();
    }

    @Override
    public AutomationExecution createOrFindExecution(AutomationExecution execution) {
        jdbc.update("""
                INSERT INTO platform_automation_executions(
                    id, schedule_id, tenant_id, owner_id, agent_id,
                    fire_key, scheduled_for, trigger_type, state, operation_hash, approval_id,
                    conversation_id, dispatch_run_id, continuation_id, agent_run_id,
                    started_at, completed_at, error, input_tokens, output_tokens,
                    revision, created_at, updated_at)
                VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?,
                    CAST(? AS UUID), ?, ?, CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(schedule_id, fire_key) DO NOTHING
                """,
                execution.id(), execution.scheduleId(), execution.tenantId(), execution.ownerId(),
                execution.agentId(), execution.fireKey(),
                timestamp(execution.scheduledFor()), execution.triggerType().name(),
                execution.state().name(), execution.operationHash(), execution.approvalId(),
                execution.conversationId(), execution.dispatchRunId(), execution.continuationId(),
                execution.agentRunId(), timestamp(execution.startedAt()),
                timestamp(execution.completedAt()), execution.error(), execution.inputTokens(),
                execution.outputTokens(), execution.revision(), timestamp(execution.createdAt()),
                timestamp(execution.updatedAt()));
        return findExecutionByFireKey(execution.scheduleId(), execution.fireKey()).orElseThrow();
    }

    @Override
    public Optional<AutomationExecution> findExecution(String executionId) {
        return jdbc.query("SELECT " + EXECUTION_COLUMNS
                        + " FROM platform_automation_executions WHERE id = CAST(? AS UUID)",
                this::mapExecution, executionId).stream().findFirst();
    }

    @Override
    public Optional<AutomationExecution> findExecutionByFireKey(
            String scheduleId, String fireKey) {
        return jdbc.query("SELECT " + EXECUTION_COLUMNS
                        + " FROM platform_automation_executions "
                        + "WHERE schedule_id = CAST(? AS UUID) AND fire_key = ?",
                this::mapExecution, scheduleId, fireKey).stream().findFirst();
    }

    @Override
    public List<AutomationExecution> findExecutions(String scheduleId, int limit) {
        return jdbc.query("SELECT " + EXECUTION_COLUMNS
                        + " FROM platform_automation_executions "
                        + "WHERE schedule_id = CAST(? AS UUID) ORDER BY created_at DESC, id LIMIT ?",
                this::mapExecution, scheduleId, limit);
    }

    @Override
    public Optional<AutomationExecution> lockNextApprovalRequired() {
        return jdbc.query("SELECT " + EXECUTION_COLUMNS
                        + " FROM platform_automation_executions "
                        + "WHERE state = 'APPROVAL_REQUIRED' ORDER BY updated_at, id "
                        + "LIMIT 1 FOR UPDATE SKIP LOCKED",
                this::mapExecution).stream().findFirst();
    }

    @Override
    public Optional<AutomationExecution> transitionExecution(
            AutomationExecution execution, long expectedRevision) {
        return jdbc.query("""
                UPDATE platform_automation_executions
                   SET state = ?, approval_id = CAST(? AS UUID), conversation_id = ?,
                       dispatch_run_id = ?, continuation_id = CAST(? AS UUID), agent_run_id = ?,
                       started_at = ?, completed_at = ?, error = ?, input_tokens = ?,
                       output_tokens = ?, revision = revision + 1, updated_at = ?
                 WHERE id = CAST(? AS UUID) AND revision = ?
                RETURNING """ + " " + EXECUTION_COLUMNS,
                this::mapExecution,
                execution.state().name(), execution.approvalId(), execution.conversationId(),
                execution.dispatchRunId(), execution.continuationId(), execution.agentRunId(),
                timestamp(execution.startedAt()), timestamp(execution.completedAt()),
                execution.error(), execution.inputTokens(), execution.outputTokens(),
                timestamp(execution.updatedAt()), execution.id(), expectedRevision)
                .stream().findFirst();
    }

    @Override
    public List<AutomationExecution> findRunningBefore(Instant cutoff, int limit) {
        return jdbc.query("SELECT " + EXECUTION_COLUMNS
                        + " FROM platform_automation_executions "
                        + "WHERE state = 'RUNNING' AND started_at < ? "
                        + "ORDER BY started_at, id LIMIT ?",
                this::mapExecution, timestamp(cutoff), limit);
    }

    @Override
    public Optional<AutomationSchedule> recordOutcome(
            String scheduleId,
            Instant completedAt,
            AutomationExecutionState state,
            String error) {
        return jdbc.query("""
                UPDATE platform_automation_schedules
                   SET last_run_at = ?, last_run_status = ?, last_error = ?,
                       run_count = run_count + 1, revision = revision + 1, updated_at = ?
                 WHERE id = CAST(? AS UUID)
                RETURNING """ + " " + SCHEDULE_COLUMNS,
                this::mapSchedule,
                timestamp(completedAt), state.name(), error, timestamp(completedAt),
                scheduleId).stream().findFirst();
    }

    private AutomationSchedule mapSchedule(ResultSet row, int rowNumber) throws SQLException {
        return new AutomationSchedule(
                row.getString("id"), row.getString("tenant_id"), row.getString("owner_id"),
                row.getString("agent_id"), row.getString("description"), row.getString("prompt"),
                AutomationScheduleType.valueOf(row.getString("schedule_type")),
                row.getString("cron_expression"), instant(row.getTimestamp("scheduled_at")),
                row.getString("timezone"), AutomationScheduleState.valueOf(row.getString("state")),
                instant(row.getTimestamp("next_fire_at")), instant(row.getTimestamp("last_run_at")),
                row.getString("last_run_status"), row.getString("last_error"),
                row.getLong("run_count"), row.getInt("max_retries"), row.getLong("revision"),
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")),
                instant(row.getTimestamp("archived_at")));
    }

    private AutomationExecution mapExecution(ResultSet row, int rowNumber) throws SQLException {
        return new AutomationExecution(
                row.getString("id"), row.getString("schedule_id"), row.getString("tenant_id"),
                row.getString("owner_id"), row.getString("agent_id"),
                null, row.getString("fire_key"),
                instant(row.getTimestamp("scheduled_for")),
                AutomationTriggerType.valueOf(row.getString("trigger_type")),
                AutomationExecutionState.valueOf(row.getString("state")),
                row.getString("operation_hash"), row.getString("approval_id"),
                row.getString("conversation_id"), row.getString("dispatch_run_id"),
                row.getString("continuation_id"), row.getString("agent_run_id"),
                instant(row.getTimestamp("started_at")), instant(row.getTimestamp("completed_at")),
                row.getString("error"), row.getInt("input_tokens"),
                row.getInt("output_tokens"), row.getLong("revision"),
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
