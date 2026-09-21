package com.spaceagent.platform.conversation.infrastructure.persistence;

import com.spaceagent.platform.conversation.domain.ConversationContextSnapshot;
import com.spaceagent.platform.conversation.domain.ConversationContextSnapshotRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * Authoritative PostgreSQL-backed conversation context snapshot repository.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresConversationContextSnapshotRepository
        implements ConversationContextSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresConversationContextSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ConversationContextSnapshot> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, version, summary, from_message_sequence,
                       to_message_sequence, token_count, checksum, created_at
                FROM platform_conversation_context_snapshots
                WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    @Override
    public Optional<ConversationContextSnapshot> findLatestByConversationId(
            String conversationId) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, version, summary, from_message_sequence,
                       to_message_sequence, token_count, checksum, created_at
                FROM platform_conversation_context_snapshots
                WHERE conversation_id = ?
                ORDER BY version DESC
                LIMIT 1
                """, this::map, conversationId).stream().findFirst();
    }

    @Override
    public ConversationContextSnapshot save(ConversationContextSnapshot snapshot) {
        jdbcTemplate.update("""
                INSERT INTO platform_conversation_context_snapshots (
                    id, conversation_id, version, summary, from_message_sequence,
                    to_message_sequence, token_count, checksum, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (conversation_id, version) DO NOTHING
                """,
                snapshot.id(), snapshot.conversationId(), snapshot.version(),
                snapshot.summary(), snapshot.fromMessageSequence(),
                snapshot.toMessageSequence(), snapshot.tokenCount(),
                snapshot.checksum(), Timestamp.from(snapshot.createdAt()));
        return jdbcTemplate.query("""
                SELECT id, conversation_id, version, summary, from_message_sequence,
                       to_message_sequence, token_count, checksum, created_at
                FROM platform_conversation_context_snapshots
                WHERE conversation_id = ? AND version = ?
                """, this::map, snapshot.conversationId(), snapshot.version())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Conversation context snapshot insert returned no row"));
    }

    private ConversationContextSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationContextSnapshot(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getInt("version"),
                rs.getString("summary"),
                rs.getInt("from_message_sequence"),
                rs.getInt("to_message_sequence"),
                rs.getInt("token_count"),
                rs.getString("checksum"),
                rs.getTimestamp("created_at").toInstant());
    }
}
