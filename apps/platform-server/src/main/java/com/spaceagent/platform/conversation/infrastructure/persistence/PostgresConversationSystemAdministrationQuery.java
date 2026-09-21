package com.spaceagent.platform.conversation.infrastructure.persistence;

import com.spaceagent.platform.conversation.domain.ConversationSystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresConversationSystemAdministrationQuery implements ConversationSystemAdministrationQuery {
    private final JdbcTemplate jdbc;
    public PostgresConversationSystemAdministrationQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public OverviewRow overview() {
        return jdbc.queryForObject("""
                SELECT count(*) conversations,
                       count(*) FILTER (WHERE status = 'ACTIVE') active_conversations,
                       (SELECT count(*) FROM platform_messages) messages
                FROM platform_conversations
                """, (rs, row) -> new OverviewRow(rs.getLong("conversations"),
                rs.getLong("active_conversations"), rs.getLong("messages")));
    }
    @Override public PageRows<ResourceRow> conversationsByOwner(
            String userId, int offset, int limit) {
        var items = jdbc.query("""
                SELECT conversation.id, conversation.tenant_id,
                       conversation.project_id parent_id, NULL::text name,
                       conversation.status, CASE WHEN conversation.project_id IS NULL
                         THEN 'CHAT' ELSE 'PROJECT' END relation,
                       conversation.created_at, conversation.updated_at,
                       NULL::text safe_error_code,
                       (SELECT count(*) FROM platform_messages message
                         WHERE message.conversation_id = conversation.id) primary_count,
                       (SELECT count(*) FROM platform_conversation_context_snapshots snapshot
                         WHERE snapshot.conversation_id = conversation.id) secondary_count
                FROM platform_conversations conversation WHERE conversation.user_id = ?
                ORDER BY conversation.updated_at DESC, conversation.id LIMIT ? OFFSET ?
                """, (rs, row) -> new ResourceRow(rs.getString("id"),
                rs.getString("tenant_id"), rs.getString("parent_id"), rs.getString("name"),
                rs.getString("status"), rs.getString("relation"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("safe_error_code"),
                rs.getLong("primary_count"), rs.getLong("secondary_count")), userId, limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform_conversations WHERE user_id = ?", Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }
    @Override public DeletionEvidenceRow deletionEvidence(String userId) {
        return jdbc.queryForObject("""
                SELECT count(*) conversations,
                       (SELECT count(*) FROM platform_messages message
                         JOIN platform_conversations conversation
                           ON conversation.id = message.conversation_id
                        WHERE conversation.user_id = ?) messages
                FROM platform_conversations WHERE user_id = ?
                """, (rs, row) -> new DeletionEvidenceRow(rs.getLong("conversations"),
                rs.getLong("messages")), userId, userId);
    }
}
