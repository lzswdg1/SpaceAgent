package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.domain.AgentDelegation;
import com.spaceagent.platform.runtime.domain.AgentDelegationRepository;
import com.spaceagent.platform.runtime.domain.AgentDelegationState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAgentDelegationRepository implements AgentDelegationRepository {

    private final JdbcTemplate jdbc;

    public PostgresAgentDelegationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(AgentDelegation delegation) {
        jdbc.update("""
                INSERT INTO platform_agent_delegations(
                    id,tenant_id,parent_run_id,child_run_id,task_plan_id,plan_step_id,
                    child_task_id,target_agent_id,workspace_id,
                    handoff_id,state,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,?,?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                    ?,CAST(? AS UUID),?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    state=EXCLUDED.state,updated_at=EXCLUDED.updated_at
                """,
                delegation.id(),
                delegation.tenantId(),
                delegation.parentRunId(),
                delegation.childRunId(),
                delegation.taskPlanId(),
                delegation.planStepId(),
                delegation.childTaskId(),
                delegation.targetAgentId(),
                delegation.workspaceId(),
                delegation.handoffId(),
                delegation.state().name(),
                Timestamp.from(delegation.createdAt()),
                Timestamp.from(delegation.updatedAt()));
    }

    @Override
    public Optional<AgentDelegation> findById(String id) {
        return jdbc.query(
                "SELECT * FROM platform_agent_delegations WHERE id=CAST(? AS UUID)",
                this::map,
                id).stream().findFirst();
    }

    @Override
    public List<AgentDelegation> findByParentRunId(String parentRunId) {
        return jdbc.query(
                "SELECT * FROM platform_agent_delegations WHERE parent_run_id=? ORDER BY created_at,id",
                this::map,
                parentRunId);
    }

    private AgentDelegation map(ResultSet result, int row) throws SQLException {
        return new AgentDelegation(
                result.getString("id"),
                result.getString("tenant_id"),
                result.getString("parent_run_id"),
                result.getString("child_run_id"),
                result.getString("task_plan_id"),
                result.getString("plan_step_id"),
                result.getString("child_task_id"),
                result.getString("target_agent_id"),
                result.getString("workspace_id"),
                result.getString("handoff_id"),
                AgentDelegationState.valueOf(result.getString("state")),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }
}
