package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignment;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignmentRepository;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignmentSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectPlanStepAssignmentRepository implements ProjectPlanStepAssignmentRepository {
    private static final String COLUMNS = "id,tenant_id,owner_id,project_id,task_plan_id,plan_step_id,"
            + "revision,source,agent_id,reviewer_agent_id,"
            + "model_pool_id,capability_hash,configuration_hash,assignment_hash,assigned_at";
    private final JdbcTemplate jdbc;

    public PostgresProjectPlanStepAssignmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void insert(ProjectPlanStepAssignment value) {
        jdbc.update("""
                INSERT INTO platform_project_plan_step_assignments(%s)
                VALUES(CAST(? AS UUID),?,?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),?,?,?,
                       ?,CAST(? AS UUID),?,?,?,?)
                """.formatted(COLUMNS), value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.taskPlanId(), value.planStepId(), value.revision(), value.source().name(), value.agentId(),
                value.reviewerAgentId(), value.modelPoolId(),
                value.capabilityHash(), value.configurationHash(), value.assignmentHash(), Timestamp.from(value.assignedAt()));
    }

    @Override public Optional<ProjectPlanStepAssignment> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_project_plan_step_assignments WHERE id=CAST(? AS UUID)", this::map, id).stream().findFirst();
    }

    @Override public Optional<ProjectPlanStepAssignment> findLatest(String tenantId, String ownerId, String projectId, String taskPlanId, String planStepId) {
        return queryLatest(tenantId, ownerId, projectId, taskPlanId, planStepId, "");
    }

    @Override @Transactional public Optional<ProjectPlanStepAssignment> findLatestForUpdate(String tenantId, String ownerId, String projectId, String taskPlanId, String planStepId) {
        return queryLatest(tenantId, ownerId, projectId, taskPlanId, planStepId, " FOR UPDATE");
    }

    private Optional<ProjectPlanStepAssignment> queryLatest(String tenantId, String ownerId, String projectId, String taskPlanId, String planStepId, String lock) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_project_plan_step_assignments WHERE tenant_id=? AND owner_id=? AND project_id=CAST(? AS UUID) AND task_plan_id=CAST(? AS UUID) AND plan_step_id=CAST(? AS UUID) ORDER BY revision DESC LIMIT 1" + lock, this::map, tenantId, ownerId, projectId, taskPlanId, planStepId).stream().findFirst();
    }

    private ProjectPlanStepAssignment map(ResultSet row, int ignored) throws SQLException {
        return new ProjectPlanStepAssignment(row.getString("id"), row.getString("tenant_id"), row.getString("owner_id"), row.getString("project_id"), row.getString("task_plan_id"), row.getString("plan_step_id"), row.getLong("revision"), ProjectPlanStepAssignmentSource.valueOf(row.getString("source")), row.getString("agent_id"), null, row.getString("reviewer_agent_id"), null, row.getString("model_pool_id"), row.getString("capability_hash"), row.getString("configuration_hash"), row.getString("assignment_hash"), row.getTimestamp("assigned_at").toInstant());
    }
}
