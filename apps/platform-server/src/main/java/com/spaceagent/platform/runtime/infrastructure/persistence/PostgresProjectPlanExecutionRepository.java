package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.domain.ProjectPlanExecution;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionRepository;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionDesiredState;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectPlanExecutionRepository implements ProjectPlanExecutionRepository {
    private static final String SELECT = "SELECT * FROM platform_project_plan_executions";
    private static final String INSERT = """
            INSERT INTO platform_project_plan_executions(
              id,tenant_id,owner_id,project_id,project_directory_id,conversation_id,
              source_repository_id,root_task_id,task_plan_id,agent_id,reviewer_agent_id,
              base_ref,idempotency_hash,input_hash,state,
              desired_state,control_reason,safe_error_code,attempt,revision,created_at,
              started_at,updated_at,completed_at)
            VALUES(CAST(:id AS UUID),:tenant,:owner,CAST(:project AS UUID),
              CAST(:directory AS UUID),:conversation,CAST(:source AS UUID),
              CAST(:rootTask AS UUID),CAST(:taskPlan AS UUID),:agent,:reviewerAgent,
              :baseRef,:idempotencyHash,:inputHash,:state,:desiredState,:controlReason,:safeErrorCode,
              :attempt,:revision,:createdAt,:startedAt,:updatedAt,:completedAt)
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public PostgresProjectPlanExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void insert(ProjectPlanExecution execution) {
        named.update(INSERT, params(execution));
    }

    @Override
    public boolean insertIfAbsent(ProjectPlanExecution execution) {
        return named.update(INSERT + " ON CONFLICT DO NOTHING",
                params(execution)) == 1;
    }

    @Override
    public Optional<ProjectPlanExecution> findById(String id) {
        return jdbc.query(SELECT + " WHERE id=CAST(? AS UUID)", this::map, id)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<ProjectPlanExecution> findByIdForUpdate(String id) {
        return jdbc.query(SELECT + " WHERE id=CAST(? AS UUID) FOR UPDATE", this::map, id)
                .stream().findFirst();
    }

    @Override
    public Optional<ProjectPlanExecution> findByTaskPlanId(
            String tenantId, String ownerId, String taskPlanId) {
        return jdbc.query(SELECT + " WHERE tenant_id=? AND owner_id=? "
                        + "AND task_plan_id=CAST(? AS UUID)",
                this::map, tenantId, ownerId, taskPlanId).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<ProjectPlanExecution> findByTaskPlanIdForUpdate(
            String tenantId, String ownerId, String taskPlanId) {
        return jdbc.query(SELECT + " WHERE tenant_id=? AND owner_id=? "
                        + "AND task_plan_id=CAST(? AS UUID) FOR UPDATE",
                this::map, tenantId, ownerId, taskPlanId).stream().findFirst();
    }

    @Override
    public List<ProjectPlanExecution> findByProject(
            String projectId, String ownerId, int offset, int limit) {
        return jdbc.query(SELECT + " WHERE project_id=CAST(? AS UUID) AND owner_id=? "
                        + "ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::map, projectId, ownerId, limit, offset);
    }

    @Override
    public long countByProject(String projectId, String ownerId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM platform_project_plan_executions "
                        + "WHERE project_id=CAST(? AS UUID) AND owner_id=?",
                Long.class, projectId, ownerId);
        return count == null ? 0 : count;
    }

    @Override
    public List<ProjectPlanExecution> findControlTransitions(int limit) {
        return jdbc.query(SELECT + " WHERE state IN ('PAUSING','CANCELLING') "
                        + "ORDER BY updated_at,id LIMIT ?",
                this::map, Math.max(1, Math.min(100, limit)));
    }

    @Override
    public void saveLifecycle(ProjectPlanExecution expected, ProjectPlanExecution updated) {
        MapSqlParameterSource parameters = lifecycleParams(updated)
                .addValue("expectedId", expected.id())
                .addValue("expectedTenant", expected.tenantId())
                .addValue("expectedOwner", expected.ownerId())
                .addValue("expectedRevision", expected.revision());
        if (named.update(updateLifecycleSql() + " WHERE id=CAST(:expectedId AS UUID) "
                + "AND tenant_id=:expectedTenant AND owner_id=:expectedOwner "
                + "AND revision=:expectedRevision", parameters) != 1) {
            throw new IllegalStateException("Project Plan Execution revision conflict");
        }
    }

    @Override
    public boolean saveStateIfMatch(
            String id, String tenantId, String ownerId, long revision,
            ProjectPlanExecution updated) {
        MapSqlParameterSource parameters = lifecycleParams(updated)
                .addValue("expectedId", id)
                .addValue("expectedTenant", tenantId)
                .addValue("expectedOwner", ownerId)
                .addValue("expectedRevision", revision);
        return named.update(updateLifecycleSql() + " WHERE id=CAST(:expectedId AS UUID) "
                + "AND tenant_id=:expectedTenant AND owner_id=:expectedOwner "
                + "AND revision=:expectedRevision", parameters) == 1;
    }

    @Override
    public int cleanupOldCompleted(Instant before, int limit) {
        return jdbc.update("""
                WITH obsolete AS (
                  SELECT id FROM platform_project_plan_executions
                   WHERE state IN ('COMPLETED','FAILED','BLOCKED','CANCELLED')
                     AND completed_at<?
                   ORDER BY completed_at,id
                   FOR UPDATE SKIP LOCKED
                   LIMIT ?
                )
                DELETE FROM platform_project_plan_executions execution
                 USING obsolete
                 WHERE execution.id=obsolete.id
                """, timestamp(before), limit);
    }

    private static String updateLifecycleSql() {
        return """
                UPDATE platform_project_plan_executions
                   SET state=:state,desired_state=:desiredState,control_reason=:controlReason,
                       safe_error_code=:safeErrorCode,attempt=:attempt,
                       revision=:revision,started_at=:startedAt,updated_at=:updatedAt,
                       completed_at=:completedAt
                """;
    }

    private static MapSqlParameterSource params(ProjectPlanExecution execution) {
        return lifecycleParams(execution)
                .addValue("id", execution.id())
                .addValue("tenant", execution.tenantId())
                .addValue("owner", execution.ownerId())
                .addValue("project", execution.projectId())
                .addValue("directory", execution.projectDirectoryId())
                .addValue("conversation", execution.conversationId())
                .addValue("source", execution.sourceRepositoryId())
                .addValue("rootTask", execution.rootTaskId())
                .addValue("taskPlan", execution.taskPlanId())
                .addValue("agent", execution.agentId())
                .addValue("reviewerAgent", execution.reviewerAgentId())
                .addValue("baseRef", execution.baseRef())
                .addValue("idempotencyHash", execution.idempotencyHash())
                .addValue("inputHash", execution.inputHash())
                .addValue("createdAt", timestamp(execution.createdAt()));
    }

    private static MapSqlParameterSource lifecycleParams(ProjectPlanExecution execution) {
        return new MapSqlParameterSource()
                .addValue("state", execution.state().name())
                .addValue("desiredState", execution.desiredState().name())
                .addValue("controlReason", execution.controlReason())
                .addValue("safeErrorCode", execution.safeErrorCode())
                .addValue("attempt", execution.attempt())
                .addValue("revision", execution.revision())
                .addValue("startedAt", timestamp(execution.startedAt()))
                .addValue("updatedAt", timestamp(execution.updatedAt()))
                .addValue("completedAt", timestamp(execution.completedAt()));
    }

    private ProjectPlanExecution map(ResultSet row, int number) throws SQLException {
        return new ProjectPlanExecution(
                row.getString("id"), row.getString("tenant_id"), row.getString("owner_id"),
                row.getString("project_id"), row.getString("project_directory_id"),
                row.getString("conversation_id"), row.getString("source_repository_id"),
                row.getString("root_task_id"), row.getString("task_plan_id"),
                row.getString("agent_id"), null, row.getString("reviewer_agent_id"), row.getString("base_ref"),
                row.getString("idempotency_hash"), row.getString("input_hash"),
                ProjectPlanExecutionState.valueOf(row.getString("state")),
                row.getString("safe_error_code"), row.getInt("attempt"),
                row.getLong("revision"), row.getTimestamp("created_at").toInstant(),
                instant(row.getTimestamp("started_at")),
                row.getTimestamp("updated_at").toInstant(),
                instant(row.getTimestamp("completed_at")),
                ProjectPlanExecutionDesiredState.valueOf(row.getString("desired_state")),
                row.getString("control_reason"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
