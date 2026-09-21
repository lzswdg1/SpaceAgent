package com.spaceagent.platform.conversation.infrastructure.persistence;

import com.spaceagent.platform.conversation.domain.Message;
import com.spaceagent.platform.conversation.domain.MessageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Authoritative PostgreSQL persistence for conversation messages.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresMessageRepository implements MessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, sequence_number, role, content, created_at
                FROM platform_messages
                WHERE conversation_id = ? AND role <> 'ASSISTANT_PENDING'
                ORDER BY sequence_number
                """, this::map, conversationId);
    }

    @Override
    public List<Message> findRecentByConversationId(String conversationId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, sequence_number, role, content, created_at
                FROM (
                    SELECT id, conversation_id, sequence_number, role, content, created_at
                    FROM platform_messages
                    WHERE conversation_id = ? AND role <> 'ASSISTANT_PENDING'
                    ORDER BY sequence_number DESC
                    LIMIT ?
                ) recent
                ORDER BY sequence_number
                """, this::map, conversationId, limit);
    }

    @Override
    public Optional<Message> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, sequence_number, role, content, created_at
                FROM platform_messages
                WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    @Override public List<Message> findBeforeSequence(String conversationId,int beforeSequence,int limit) {
        return jdbcTemplate.query("""
                SELECT id,conversation_id,sequence_number,role,content,created_at FROM platform_messages
                WHERE conversation_id=? AND sequence_number<? AND role<>'ASSISTANT_PENDING'
                ORDER BY sequence_number DESC LIMIT ?
                """,this::map,conversationId,beforeSequence,limit);
    }

    @Override
    public void save(Message message) {
        jdbcTemplate.update("""
                INSERT INTO platform_messages (
                    id, conversation_id, sequence_number, role, content, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                message.id(),
                message.conversationId(),
                message.sequence(),
                message.role(),
                message.content(),
                Timestamp.from(message.createdAt()));
    }

    @Override
    public int nextSequence(String conversationId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_number), -1) + 1 FROM platform_messages "
                        + "WHERE conversation_id = ?",
                Integer.class,
                conversationId);
        return value == null ? 0 : value;
    }

    @Override
    public boolean completeReserved(String messageId, String content, java.time.Instant completedAt) {
        return jdbcTemplate.update("""
                UPDATE platform_messages
                   SET role = 'ASSISTANT', content = ?, created_at = ?
                 WHERE id = ? AND role IN ('ASSISTANT_PENDING','ASSISTANT_PARTIAL')
                """, content, Timestamp.from(completedAt), messageId) == 1;
    }

    @Override public boolean updateReservedDraft(String messageId,String content,java.time.Instant at) {
        return jdbcTemplate.update("""
                UPDATE platform_messages SET role='ASSISTANT_PARTIAL',content=?,created_at=?
                WHERE id=? AND role IN ('ASSISTANT_PENDING','ASSISTANT_PARTIAL')
                """,content,Timestamp.from(at),messageId)==1;
    }

    @Override
    public long countByConversationId(String conversationId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_messages "
                        + "WHERE conversation_id = ? AND role <> 'ASSISTANT_PENDING'",
                Long.class,
                conversationId);
        return count == null ? 0 : count;
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("DELETE FROM platform_messages WHERE conversation_id = ?", conversationId);
    }

    private Message map(ResultSet rs, int rowNum) throws SQLException {
        return new Message(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getInt("sequence_number"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getTimestamp("created_at").toInstant());
    }
}
