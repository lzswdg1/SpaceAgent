package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.util.*;

@Repository
@ConditionalOnProperty(prefix="platform", name="persistence", havingValue="postgres")
public class PostgresKnowledgeIndexCatalogRepository implements KnowledgeIndexCatalogRepository {
    private final JdbcTemplate jdbc;
    public PostgresKnowledgeIndexCatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<KnowledgeIndexGeneration> findGeneration(String base,String id) {
        return jdbc.query("SELECT * FROM platform_knowledge_index_generations WHERE base_id=? AND id=?",this::generation,base,id).stream().findFirst();
    }
    @Override public Optional<KnowledgeDocumentScope> findDocumentScope(String document) {
        return jdbc.query("SELECT * FROM platform_knowledge_document_scopes WHERE document_id=?", this::scope, document).stream().findFirst();
    }
    @Override @Transactional public KnowledgeDocumentScope insertScopeIfAbsent(KnowledgeDocumentScope scope) {
        jdbc.update("""
                INSERT INTO platform_knowledge_document_scopes(document_id,base_id,original_owner_id,provenance,assigned_at)
                VALUES (?,?,?,?,?) ON CONFLICT(document_id) DO NOTHING
                """, scope.documentId(), scope.baseId(), scope.originalOwnerId(), scope.provenance(), Timestamp.from(scope.assignedAt()));
        return findDocumentScope(scope.documentId()).orElseThrow();
    }
    @Override public List<KnowledgeDocumentScope> listDocuments(String base, int offset, int limit) {
        return jdbc.query("SELECT * FROM platform_knowledge_document_scopes WHERE base_id=? ORDER BY document_id LIMIT ? OFFSET ?",
                this::scope, base, limit, offset);
    }
    @Override @Transactional public KnowledgeEmbeddingSpace insertSpaceIfAbsent(KnowledgeEmbeddingSpace space) {
        jdbc.update("""
                INSERT INTO platform_knowledge_embedding_spaces(id,base_id,provider_id,model_id,model_revision,
                    provider_fingerprint,dimensions,preprocessing_hash,fingerprint,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(base_id,fingerprint) DO NOTHING
                """, space.id(), space.baseId(), space.providerId(), space.modelId(), space.modelRevision(), space.providerFingerprint(),
                space.dimensions(), space.preprocessingHash(), space.fingerprint(), space.createdBy(), Timestamp.from(space.createdAt()));
        return jdbc.query("SELECT * FROM platform_knowledge_embedding_spaces WHERE base_id=? AND fingerprint=?",
                this::space, space.baseId(), space.fingerprint()).stream().findFirst().orElseThrow();
    }
    @Override public Optional<KnowledgeEmbeddingSpace> findSpace(String base, String id) {
        return jdbc.query("SELECT * FROM platform_knowledge_embedding_spaces WHERE base_id=? AND id=?", this::space, base, id).stream().findFirst();
    }
    @Override public List<KnowledgeEmbeddingSpace> listSpaces(String base, int offset, int limit) {
        return jdbc.query("SELECT * FROM platform_knowledge_embedding_spaces WHERE base_id=? ORDER BY id LIMIT ? OFFSET ?",
                this::space, base, limit, offset);
    }
    @Override @Transactional public KnowledgeIndexGeneration insertGenerationIfAbsent(KnowledgeIndexGeneration generation) {
        jdbc.update("""
                INSERT INTO platform_knowledge_index_generations(id,base_id,document_id,space_id,content_hash,
                    parser_fingerprint,chunking_fingerprint,fingerprint,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(base_id,fingerprint) DO NOTHING
                """, generation.id(), generation.baseId(), generation.documentId(), generation.spaceId(), generation.contentHash(),
                generation.parserFingerprint(), generation.chunkingFingerprint(), generation.fingerprint(), generation.createdBy(),
                Timestamp.from(generation.createdAt()));
        return jdbc.query("SELECT * FROM platform_knowledge_index_generations WHERE base_id=? AND fingerprint=?",
                this::generation, generation.baseId(), generation.fingerprint()).stream().findFirst().orElseThrow();
    }
    @Override public List<KnowledgeIndexGeneration> listGenerations(String base, String document, int offset, int limit) {
        return jdbc.query("SELECT * FROM platform_knowledge_index_generations WHERE base_id=? AND document_id=? ORDER BY id LIMIT ? OFFSET ?",
                this::generation, base, document, limit, offset);
    }
    private KnowledgeDocumentScope scope(ResultSet rs, int row) throws SQLException {
        return new KnowledgeDocumentScope(rs.getString("document_id"), rs.getString("base_id"), rs.getString("original_owner_id"),
                rs.getString("provenance"), rs.getTimestamp("assigned_at").toInstant());
    }
    private KnowledgeEmbeddingSpace space(ResultSet rs, int row) throws SQLException {
        return new KnowledgeEmbeddingSpace(rs.getString("id"), rs.getString("base_id"), rs.getString("provider_id"),
                rs.getString("model_id"), rs.getString("model_revision"), rs.getString("provider_fingerprint"), rs.getInt("dimensions"),
                rs.getString("preprocessing_hash"), rs.getString("fingerprint"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
    }
    private KnowledgeIndexGeneration generation(ResultSet rs, int row) throws SQLException {
        return new KnowledgeIndexGeneration(rs.getString("id"), rs.getString("base_id"), rs.getString("document_id"), rs.getString("space_id"),
                rs.getString("content_hash"), rs.getString("parser_fingerprint"), rs.getString("chunking_fingerprint"), rs.getString("fingerprint"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
    }
}
