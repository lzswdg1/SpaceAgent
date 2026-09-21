package com.spaceagent.platform.project.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.domain.PlanStep;
import com.spaceagent.platform.project.domain.PlanStepDependency;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.TaskPlan;
import com.spaceagent.platform.project.domain.TaskPlanRepository;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
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
public class PostgresTaskPlanRepository implements TaskPlanRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String PLAN_COLUMNS = """
            id, project_id, root_task_id, version_number, status,
            created_by, approved_by, approved_at,
            created_at, updated_at, tenant_id, owner_user_id, conversation_id,
            source_agent_run_id, proposal_hash, strategy_summary,
            generated_by_agent_id, generated_by_run_configuration_snapshot_id
            """;
    private static final String STEP_COLUMNS = """
            id, task_plan_id, project_id, step_key, sequence_number, child_task_id,
            required_capability, preferred_agent_id, expected_output,
            acceptance_criteria_json, approval_required, state, created_at, updated_at,
            conversation_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresTaskPlanRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public int nextVersionNumber(String rootTaskId) {
        Integer value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM platform_task_plans
                WHERE root_task_id = CAST(? AS UUID)
                """, Integer.class, rootTaskId);
        return value == null ? 1 : value;
    }

    @Override
    public void lockChatProposalSource(String sourceAgentRunId) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> { },
                sourceAgentRunId);
    }

    @Override
    public void insert(
            TaskPlan plan,
            List<PlanStep> steps,
            List<PlanStepDependency> dependencies) {
        jdbcTemplate.update("""
                INSERT INTO platform_task_plans (
                    id, project_id, root_task_id, version_number, status,
                    created_by, approved_by, approved_at,
                    created_at, updated_at, tenant_id, owner_user_id, conversation_id,
                    source_agent_run_id, proposal_hash, strategy_summary,
                    generated_by_agent_id, generated_by_run_configuration_snapshot_id
                ) VALUES (
                    CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                plan.id(), plan.projectId(), plan.rootTaskId(), plan.versionNumber(),
                plan.status().name(), plan.createdBy(),
                plan.approvedBy(), timestamp(plan.approvedAt()),
                Timestamp.from(plan.createdAt()), Timestamp.from(plan.updatedAt()),
                plan.tenantId(), plan.ownerUserId(), plan.conversationId(),
                plan.sourceAgentRunId(), plan.proposalHash(), plan.strategySummary(),
                plan.generatedByAgentId(), plan.generatedByRunConfigurationSnapshotId());
        for (PlanStep step : steps) {
            jdbcTemplate.update("""
                    INSERT INTO platform_plan_steps (
                        id, task_plan_id, project_id, step_key, sequence_number,
                        child_task_id, required_capability, preferred_agent_id,
                        expected_output, acceptance_criteria_json, approval_required,
                        state, created_at, updated_at, conversation_id
                    ) VALUES (
                        CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, ?,
                        CAST(? AS UUID), ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?
                        , ?
                    )
                    """,
                    step.id(), step.taskPlanId(), step.projectId(), step.stepKey(),
                    step.sequence(), step.childTaskId(), step.requiredCapability(),
                    step.preferredAgentId(), step.expectedOutput(),
                    writeList(step.acceptanceCriteria()), step.approvalRequired(),
                    step.state().name(), Timestamp.from(step.createdAt()),
                    Timestamp.from(step.updatedAt()), step.conversationId());
        }
        for (PlanStepDependency dependency : dependencies) {
            jdbcTemplate.update("""
                    INSERT INTO platform_plan_step_dependencies (
                        task_plan_id, step_id, depends_on_step_id
                    ) VALUES (CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID))
                    """,
                    dependency.taskPlanId(), dependency.stepId(),
                    dependency.dependsOnStepId());
        }
    }

    @Override
    public void updateLifecycle(TaskPlan plan) {
        jdbcTemplate.update("""
                UPDATE platform_task_plans
                SET status = ?, approved_by = ?, approved_at = ?, updated_at = ?
                WHERE id = CAST(? AS UUID)
                """,
                plan.status().name(), plan.approvedBy(), timestamp(plan.approvedAt()),
                Timestamp.from(plan.updatedAt()), plan.id());
    }

    @Override
    public void updateStep(PlanStep step) {
        int updated = jdbcTemplate.update("""
                UPDATE platform_plan_steps
                SET state = ?, updated_at = ?
                WHERE task_plan_id = CAST(? AS UUID) AND id = CAST(? AS UUID)
                """,
                step.state().name(), Timestamp.from(step.updatedAt()),
                step.taskPlanId(), step.id());
        if (updated != 1) {
            throw new IllegalStateException("PlanStep not found");
        }
    }

    @Override
    public Optional<TaskPlan> findById(String planId) {
        return jdbcTemplate.query(
                "SELECT " + PLAN_COLUMNS
                        + " FROM platform_task_plans WHERE id = CAST(? AS UUID)",
                this::mapPlan,
                planId).stream().findFirst();
    }

    @Override
    public Optional<TaskPlan> findByIdForUpdate(String planId) {
        return jdbcTemplate.query(
                "SELECT " + PLAN_COLUMNS
                        + " FROM platform_task_plans WHERE id = CAST(? AS UUID) FOR UPDATE",
                this::mapPlan,
                planId).stream().findFirst();
    }

    @Override
    public Optional<TaskPlan> findBySourceAgentRunId(String sourceAgentRunId) {
        return jdbcTemplate.query(
                "SELECT " + PLAN_COLUMNS
                        + " FROM platform_task_plans WHERE source_agent_run_id = ?",
                this::mapPlan,
                sourceAgentRunId).stream().findFirst();
    }

    @Override
    public List<TaskPlan> findByRootTaskId(String rootTaskId) {
        return jdbcTemplate.query(
                "SELECT " + PLAN_COLUMNS
                        + " FROM platform_task_plans WHERE root_task_id = CAST(? AS UUID)"
                        + " ORDER BY version_number",
                this::mapPlan,
                rootTaskId);
    }

    @Override
    public List<PlanStep> findSteps(String planId) {
        return jdbcTemplate.query(
                "SELECT " + STEP_COLUMNS
                        + " FROM platform_plan_steps WHERE task_plan_id = CAST(? AS UUID)"
                        + " ORDER BY sequence_number, id",
                this::mapStep,
                planId);
    }

    @Override
    public List<PlanStepDependency> findDependencies(String planId) {
        return jdbcTemplate.query("""
                SELECT task_plan_id, step_id, depends_on_step_id
                FROM platform_plan_step_dependencies
                WHERE task_plan_id = CAST(? AS UUID)
                ORDER BY step_id, depends_on_step_id
                """,
                (rs, rowNum) -> new PlanStepDependency(
                        rs.getString("task_plan_id"), rs.getString("step_id"),
                        rs.getString("depends_on_step_id")),
                planId);
    }

    private TaskPlan mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new TaskPlan(
                rs.getString("id"), rs.getString("project_id"),
                rs.getString("root_task_id"), rs.getInt("version_number"),
                TaskPlanStatus.valueOf(rs.getString("status")),
                null, rs.getString("created_by"),
                rs.getString("approved_by"), instant(rs.getTimestamp("approved_at")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("tenant_id"), rs.getString("owner_user_id"),
                rs.getString("conversation_id"), rs.getString("source_agent_run_id"),
                rs.getString("proposal_hash"), rs.getString("strategy_summary"),
                rs.getString("generated_by_agent_id"),
                rs.getString("generated_by_run_configuration_snapshot_id"));
    }

    private PlanStep mapStep(ResultSet rs, int rowNum) throws SQLException {
        return new PlanStep(
                rs.getString("id"), rs.getString("task_plan_id"),
                rs.getString("project_id"), rs.getString("step_key"),
                rs.getInt("sequence_number"), rs.getString("child_task_id"),
                rs.getString("required_capability"), rs.getString("preferred_agent_id"),
                rs.getString("expected_output"),
                readList(rs.getString("acceptance_criteria_json")),
                rs.getBoolean("approval_required"),
                PlanStepState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("conversation_id"));
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("PlanStep acceptance criteria could not be serialized", exception);
        }
    }

    private List<String> readList(String value) throws SQLException {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("PlanStep acceptance criteria could not be deserialized", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
