package com.spaceagent.platform.conversation.infrastructure.persistence;

import com.spaceagent.platform.conversation.domain.Conversation;
import com.spaceagent.platform.conversation.domain.ConversationRepository;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Authoritative PostgreSQL persistence for conversations.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresConversationRepository implements ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Conversation> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, COALESCE(project_uuid::text, project_id) AS project_id,
                       project_directory_id,
                       COALESCE(task_uuid::text, task_id) AS task_id,
                       active_task_id, tenant_id, user_id, agent_id, title, status, created_at, updated_at
                FROM platform_conversations
                WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    @Override
    public Optional<Conversation> findByIdForUpdate(String id) {
        return jdbcTemplate.query("""
                SELECT id, COALESCE(project_uuid::text, project_id) AS project_id,
                       project_directory_id,
                       COALESCE(task_uuid::text, task_id) AS task_id,
                       active_task_id, tenant_id, user_id, agent_id, title, status, created_at, updated_at
                FROM platform_conversations
                WHERE id = ?
                FOR UPDATE
                """, this::map, id).stream().findFirst();
    }

    @Override
    public List<Conversation> findByTenantAndUserId(
            String tenantId, String userId, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT id, COALESCE(project_uuid::text, project_id) AS project_id,
                       project_directory_id,
                       COALESCE(task_uuid::text, task_id) AS task_id,
                       active_task_id, tenant_id, user_id, agent_id, title, status, created_at, updated_at
                FROM platform_conversations
                WHERE tenant_id = ? AND user_id = ?
                ORDER BY updated_at DESC
                LIMIT ? OFFSET ?
                """, this::map, tenantId, userId, limit, offset);
    }

    @Override
    public long countByTenantAndUserId(String tenantId, String userId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_conversations WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, userId);
        return value == null ? 0 : value;
    }

    private static final String FILTER = """
            tenant_id=? AND user_id=?
            AND (?='ALL' OR (?='PROJECT' AND COALESCE(project_uuid::text,project_id) IS NOT NULL)
              OR (?='CHAT' AND COALESCE(project_uuid::text,project_id) IS NULL))
            AND POSITION(LOWER(?) IN LOWER(title))>0
            """;
    @Override public List<Conversation> findFiltered(String tenantId,String userId,String scope,String query,int limit,int offset) {
        return jdbcTemplate.query("""
                SELECT id,COALESCE(project_uuid::text,project_id) AS project_id,project_directory_id,
                  COALESCE(task_uuid::text,task_id) AS task_id,active_task_id,tenant_id,user_id,agent_id,
                  title,status,created_at,updated_at FROM platform_conversations WHERE
                """+FILTER+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",this::map,
                tenantId,userId,scope,scope,scope,query,limit,offset);
    }
    @Override public long countFiltered(String tenantId,String userId,String scope,String query) {
        Long count=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM platform_conversations WHERE "+FILTER,
                Long.class,tenantId,userId,scope,scope,scope,query);
        return count==null?0:count;
    }

    @Override
    public List<Conversation> findByProjectDirectoryId(
            String tenantId, String userId, String projectDirectoryId,
            int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT id, COALESCE(project_uuid::text, project_id) AS project_id,
                       project_directory_id,
                       COALESCE(task_uuid::text, task_id) AS task_id,
                       active_task_id, tenant_id, user_id, agent_id, title, status,
                       created_at, updated_at
                FROM platform_conversations
                WHERE tenant_id = ? AND user_id = ?
                  AND project_directory_id = CAST(? AS UUID)
                ORDER BY updated_at DESC
                LIMIT ? OFFSET ?
                """, this::map, tenantId, userId, projectDirectoryId, limit, offset);
    }

    @Override
    public long countByProjectDirectoryId(
            String tenantId, String userId, String projectDirectoryId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM platform_conversations
                WHERE tenant_id = ? AND user_id = ?
                  AND project_directory_id = CAST(? AS UUID)
                """, Long.class, tenantId, userId, projectDirectoryId);
        return value == null ? 0 : value;
    }

    @Override
    public void save(Conversation conversation) {
        jdbcTemplate.update("""
                INSERT INTO platform_conversations (
                    id, project_id, task_id, project_uuid, project_directory_id,
                    task_uuid, active_task_id,
                    tenant_id, user_id, agent_id, title, status, created_at, updated_at
                ) VALUES (?, ?, ?, CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID),
                          ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    project_id = EXCLUDED.project_id,
                    task_id = EXCLUDED.task_id,
                    project_uuid = EXCLUDED.project_uuid,
                    project_directory_id = EXCLUDED.project_directory_id,
                    task_uuid = EXCLUDED.task_uuid,
                    active_task_id = EXCLUDED.active_task_id,
                    tenant_id = EXCLUDED.tenant_id,
                    user_id = EXCLUDED.user_id,
                    agent_id = EXCLUDED.agent_id,
                    title = EXCLUDED.title,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """,
                conversation.id(),
                conversation.projectId(),
                conversation.taskId(),
                uuidOrNull(conversation.projectId()),
                uuidOrNull(conversation.projectDirectoryId()),
                uuidOrNull(conversation.taskId()),
                conversation.activeTaskId(),
                conversation.tenantId(),
                conversation.userId(),
                conversation.agentId(),
                conversation.title(),
                conversation.status().name(),
                Timestamp.from(conversation.createdAt()),
                Timestamp.from(conversation.updatedAt()));
    }

    @Override
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM platform_conversations WHERE id = ?", id);
    }

    private Conversation map(ResultSet rs, int rowNum) throws SQLException {
        return new Conversation(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("project_directory_id"),
                rs.getString("task_id"),
                rs.getString("active_task_id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("agent_id"),
                rs.getString("title"),
                ConversationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String uuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
