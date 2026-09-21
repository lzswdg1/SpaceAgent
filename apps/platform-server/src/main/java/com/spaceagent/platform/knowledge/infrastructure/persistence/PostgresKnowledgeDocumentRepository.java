package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.KnowledgeDocument;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentRepository;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Authoritative PostgreSQL persistence for knowledge documents.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresKnowledgeDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<KnowledgeDocument> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, owner_id, name, content_type, storage_location,
                       status, created_at, updated_at
                FROM platform_knowledge_documents
                WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    @Override
    public List<KnowledgeDocument> findByOwnerId(String ownerId) {
        return jdbcTemplate.query("""
                SELECT id, owner_id, name, content_type, storage_location,
                       status, created_at, updated_at
                FROM platform_knowledge_documents
                WHERE owner_id = ?
                ORDER BY created_at DESC, id DESC
                """, this::map, ownerId);
    }

    @Override
    public List<KnowledgeDocument> findReadyByOwnerId(String ownerId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, owner_id, name, content_type, storage_location,
                       status, created_at, updated_at
                FROM platform_knowledge_documents
                WHERE owner_id = ? AND status = 'READY'
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, this::map, ownerId, Math.max(1, Math.min(limit, 64)));
    }

    @Override
    public List<KnowledgeDocument> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query("""
                SELECT id, owner_id, name, content_type, storage_location,
                       status, created_at, updated_at
                FROM platform_knowledge_documents
                WHERE id IN (%s)
                """.formatted(placeholders), this::map, ids.toArray());
    }

    @Override
    public void save(KnowledgeDocument document) {
        jdbcTemplate.update("""
                INSERT INTO platform_knowledge_documents (
                    id, owner_id, name, content_type, storage_location, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    owner_id = EXCLUDED.owner_id,
                    name = EXCLUDED.name,
                    content_type = EXCLUDED.content_type,
                    storage_location = EXCLUDED.storage_location,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                """,
                document.id(), document.ownerId(), document.name(), document.contentType(),
                document.storageLocation(), document.status().name(),
                Timestamp.from(document.createdAt()), Timestamp.from(document.updatedAt()));
    }

    @Override
    public void updateStatus(String documentId, KnowledgeDocumentStatus status, String errorReason) {
        jdbcTemplate.update("""
                UPDATE platform_knowledge_documents
                SET status = ?, error_reason = ?, updated_at = ?
                WHERE id = ?
                """, status.name(), errorReason, Timestamp.from(java.time.Instant.now()), documentId);
    }

    @Override
    public void delete(String documentId) {
        jdbcTemplate.update("DELETE FROM platform_knowledge_documents WHERE id = ?", documentId);
    }

    private KnowledgeDocument map(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeDocument(
                rs.getString("id"),
                rs.getString("owner_id"),
                rs.getString("name"),
                rs.getString("content_type"),
                rs.getString("storage_location"),
                KnowledgeDocumentStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
