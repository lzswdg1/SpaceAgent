package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.KnowledgeSystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresKnowledgeSystemAdministrationQuery
        implements KnowledgeSystemAdministrationQuery {
    private final JdbcTemplate jdbc;

    public PostgresKnowledgeSystemAdministrationQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public PageRows<ResourceRow> documentsByOwner(
            String userId, int offset, int limit) {
        var items = jdbc.query("""
                SELECT document.id, document.content_type relation, document.status,
                       document.created_at, document.updated_at,
                       CASE WHEN document.error_reason ~ '^[A-Z0-9_]{1,160}$'
                         THEN document.error_reason END safe_error_code,
                       (SELECT count(*) FROM platform_knowledge_chunks chunk
                         WHERE chunk.document_id = document.id) chunk_count
                FROM platform_knowledge_documents document WHERE document.owner_id = ?
                ORDER BY document.updated_at DESC, document.id LIMIT ? OFFSET ?
                """, (rs, row) -> new ResourceRow(rs.getString("id"), rs.getString("relation"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("safe_error_code"),
                rs.getLong("chunk_count")), userId, limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform_knowledge_documents WHERE owner_id = ?",
                Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }
}
