package com.spaceagent.platform.project.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.domain.Task;
import com.spaceagent.platform.project.domain.TaskRepository;
import com.spaceagent.platform.project.domain.TaskState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** Authoritative PostgreSQL adapter for PROJECT and CHAT scoped Tasks. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresTaskRepository implements TaskRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String COLUMNS = """
            id, project_id, tenant_id, owner_user_id, conversation_id, source_message_id,
            parent_task_id, title, goal, description,
            constraints_json, acceptance_criteria_json, current_task_plan_id,
            state, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresTaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(Task task) {
        jdbcTemplate.update("""
                INSERT INTO platform_tasks (
                    id, project_id, tenant_id, owner_user_id, conversation_id, source_message_id,
                    parent_task_id, title, goal, description,
                    constraints_json, acceptance_criteria_json, current_task_plan_id,
                    state, created_at, updated_at
                ) VALUES (
                    CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, CAST(? AS UUID), ?, ?, ?,
                    CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS UUID), ?, ?, ?
                )
                ON CONFLICT (id) DO UPDATE SET
                    title = EXCLUDED.title,
                    goal = EXCLUDED.goal,
                    description = EXCLUDED.description,
                    constraints_json = EXCLUDED.constraints_json,
                    acceptance_criteria_json = EXCLUDED.acceptance_criteria_json,
                    current_task_plan_id = EXCLUDED.current_task_plan_id,
                    state = EXCLUDED.state,
                    updated_at = EXCLUDED.updated_at
                """,
                task.id(), task.projectId(), task.tenantId(), task.ownerUserId(),
                task.conversationId(), task.sourceMessageId(), task.parentTaskId(),
                task.title(), task.goal(), task.description(), writeList(task.constraints()),
                writeList(task.acceptanceCriteria()), task.currentTaskPlanId(),
                task.state().name(),
                Timestamp.from(task.createdAt()), Timestamp.from(task.updatedAt()));
    }

    @Override
    public Task createOrFindChatRoot(Task task) {
        List<Task> inserted = jdbcTemplate.query("""
                INSERT INTO platform_tasks (
                    id, project_id, tenant_id, owner_user_id, conversation_id, source_message_id,
                    parent_task_id, title, goal, description,
                    constraints_json, acceptance_criteria_json, current_task_plan_id,
                    state, created_at, updated_at
                ) VALUES (
                    CAST(? AS UUID), NULL, ?, ?, ?, ?, NULL, ?, ?, NULL,
                    CAST(? AS JSONB), CAST(? AS JSONB), NULL, ?, ?, ?
                )
                ON CONFLICT (source_message_id) WHERE source_message_id IS NOT NULL DO NOTHING
                RETURNING
                """ + COLUMNS,
                this::map,
                task.id(), task.tenantId(), task.ownerUserId(), task.conversationId(),
                task.sourceMessageId(), task.title(), task.goal(),
                writeList(task.constraints()), writeList(task.acceptanceCriteria()),
                task.state().name(), Timestamp.from(task.createdAt()),
                Timestamp.from(task.updatedAt()));
        if (!inserted.isEmpty()) return inserted.getFirst();
        return findBySourceMessageId(task.sourceMessageId())
                .orElseThrow(() -> new IllegalStateException(
                        "Concurrent Chat Root Task could not be resolved"));
    }

    @Override
    public Optional<Task> findById(String taskId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_tasks WHERE id = CAST(? AS UUID)",
                this::map,
                taskId).stream().findFirst();
    }

    @Override
    public Optional<Task> findByIdForUpdate(String taskId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM platform_tasks WHERE id = CAST(? AS UUID) FOR UPDATE",
                this::map,
                taskId).stream().findFirst();
    }

    @Override
    public Optional<Task> findBySourceMessageId(String sourceMessageId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_tasks WHERE source_message_id = ?",
                this::map, sourceMessageId).stream().findFirst();
    }

    @Override
    public List<Task> findRootsByConversationId(String conversationId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_tasks "
                        + "WHERE conversation_id = ? AND parent_task_id IS NULL "
                        + "ORDER BY created_at DESC, id LIMIT ?",
                this::map, conversationId, limit);
    }

    @Override
    public List<Task> findByProjectId(String projectId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM platform_tasks "
                        + "WHERE project_id = CAST(? AS UUID) ORDER BY created_at, id",
                this::map,
                projectId);
    }

    private Task map(ResultSet rs, int rowNum) throws SQLException {
        return Task.restore(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("tenant_id"),
                rs.getString("owner_user_id"),
                rs.getString("conversation_id"),
                rs.getString("source_message_id"),
                rs.getString("parent_task_id"),
                rs.getString("title"),
                rs.getString("goal"),
                rs.getString("description"),
                readList(rs.getString("constraints_json")),
                readList(rs.getString("acceptance_criteria_json")),
                rs.getString("current_task_plan_id"),
                TaskState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Task string list could not be serialized", exception);
        }
    }

    private List<String> readList(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Task string list could not be deserialized", exception);
        }
    }
}
