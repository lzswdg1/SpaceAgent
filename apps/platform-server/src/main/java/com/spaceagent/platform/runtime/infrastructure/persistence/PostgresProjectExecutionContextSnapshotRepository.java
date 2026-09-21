package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.domain.ProjectExecutionContextSnapshot;
import com.spaceagent.platform.runtime.domain.ProjectExecutionContextSnapshotRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectExecutionContextSnapshotRepository
        implements ProjectExecutionContextSnapshotRepository {

    private static final String COLUMNS = """
            id, tenant_id, owner_id, project_id, project_directory_id, conversation_id,
            task_id, task_plan_id, plan_step_id, agent_run_id, run_configuration_snapshot_id,
            workspace_id, blueprint_id, blueprint_version, conversation_context_snapshot_id,
            conversation_context_snapshot_version, checkpoint_id,
            idempotency_hash, input_hash, snapshot_hash, payload_json, created_at
            """;

    private final JdbcTemplate jdbc;

    public PostgresProjectExecutionContextSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ProjectExecutionContextSnapshot value) {
        jdbc.update("""
                INSERT INTO platform_project_execution_context_snapshots(
                    id, tenant_id, owner_id, project_id, project_directory_id, conversation_id,
                    task_id, task_plan_id, plan_step_id, agent_run_id, run_configuration_snapshot_id,
                    workspace_id, blueprint_id, blueprint_version, conversation_context_snapshot_id,
                    conversation_context_snapshot_version, checkpoint_id,
                       idempotency_hash, input_hash, snapshot_hash, payload_json, created_at)
                VALUES(CAST(? AS UUID), ?, ?, CAST(? AS UUID), CAST(? AS UUID), ?,
                       CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), ?, CAST(? AS UUID),
                       CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.projectDirectoryId(), value.conversationId(), value.taskId(),
                value.taskPlanId(), value.planStepId(), value.agentRunId(),
                value.runConfigurationSnapshotId(), value.workspaceId(), value.blueprintId(),
                value.blueprintVersion(), value.conversationContextSnapshotId(),
                value.conversationContextSnapshotVersion(), value.checkpointId(),
                value.idempotencyHash(), value.inputHash(), value.snapshotHash(),
                value.payloadJson(), Timestamp.from(value.createdAt()));
    }

    @Override
    public Optional<ProjectExecutionContextSnapshot> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM platform_project_execution_context_snapshots "
                        + "WHERE id = CAST(? AS UUID)",
                this::map, id).stream().findFirst();
    }

    @Override
    public Optional<ProjectExecutionContextSnapshot> findByIdempotencyHash(
            String tenantId, String ownerId, String idempotencyHash) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM platform_project_execution_context_snapshots "
                        + "WHERE tenant_id = ? AND owner_id = ? AND idempotency_hash = ?",
                this::map, tenantId, ownerId, idempotencyHash).stream().findFirst();
    }

    @Override
    public Optional<ProjectExecutionContextSnapshot> findLatestByRunId(String agentRunId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM platform_project_execution_context_snapshots "
                        + "WHERE agent_run_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                this::map, agentRunId).stream().findFirst();
    }

    private ProjectExecutionContextSnapshot map(ResultSet row, int rowNumber) throws SQLException {
        return new ProjectExecutionContextSnapshot(
                row.getString("id"), row.getString("tenant_id"), row.getString("owner_id"),
                row.getString("project_id"), row.getString("project_directory_id"),
                row.getString("conversation_id"), row.getString("task_id"),
                row.getString("task_plan_id"), row.getString("plan_step_id"),
                row.getString("agent_run_id"), row.getString("run_configuration_snapshot_id"),
                row.getString("workspace_id"), row.getString("blueprint_id"),
                nullableInteger(row, "blueprint_version"),
                row.getString("conversation_context_snapshot_id"),
                nullableInteger(row, "conversation_context_snapshot_version"),
                row.getString("checkpoint_id"), row.getString("idempotency_hash"),
                row.getString("input_hash"), row.getString("snapshot_hash"),
                row.getString("payload_json"), row.getTimestamp("created_at").toInstant());
    }

    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }
}
