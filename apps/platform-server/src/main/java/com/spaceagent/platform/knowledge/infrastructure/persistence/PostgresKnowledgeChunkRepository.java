package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.domain.KnowledgeChunk;
import com.spaceagent.platform.knowledge.domain.KnowledgeChunkRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative PostgreSQL persistence for knowledge chunks.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresKnowledgeChunkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<KnowledgeChunk> findByDocumentId(String documentId) {
        return jdbcTemplate.query("""
                SELECT id, document_id, sequence_number, content, embedding_reference,
                       content_hash, embedding_model, embedding_dimensions, embedding_vector,
                       created_at
                FROM platform_knowledge_chunks
                WHERE document_id = ?
                ORDER BY sequence_number
                """, this::map, documentId);
    }

    @Override
    public List<KnowledgeChunk> findByDocumentIds(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return List.of();
        String placeholders = String.join(",",
                java.util.Collections.nCopies(documentIds.size(), "?"));
        return jdbcTemplate.query("""
                SELECT id, document_id, sequence_number, content, embedding_reference,
                       content_hash, embedding_model, embedding_dimensions, embedding_vector,
                       created_at
                FROM platform_knowledge_chunks
                WHERE document_id IN (%s)
                """.formatted(placeholders), this::map, documentIds.toArray());
    }

    @Override
    public List<SimilarityMatch> findNearestByDocumentIds(
            List<String> documentIds,
            List<Double> queryVector,
            int topK) {
        if (documentIds == null || documentIds.isEmpty()
                || queryVector == null || queryVector.isEmpty()) {
            return List.of();
        }
        if (documentIds.size() > 64 || queryVector.size() > 4_096 || topK < 1 || topK > 100) {
            throw new IllegalArgumentException("Knowledge similarity query exceeds bounds");
        }
        String placeholders = String.join(",",
                java.util.Collections.nCopies(documentIds.size(), "?"));
        String sql = """
                SELECT chunk.document_id, chunk.id, chunk.sequence_number, chunk.content,
                       chunk.embedding_model, scored.score
                FROM platform_knowledge_chunks chunk
                CROSS JOIN LATERAL (
                    SELECT COALESCE(
                        SUM((query_part.component)::double precision
                            * (chunk_part.component)::double precision)
                        / NULLIF(
                            SQRT(SUM(POWER((query_part.component)::double precision, 2)))
                            * SQRT(SUM(POWER((chunk_part.component)::double precision, 2))),
                            0),
                        0) AS score
                    FROM jsonb_array_elements_text(CAST(? AS jsonb)) WITH ORDINALITY
                         AS query_part(component, position)
                    JOIN jsonb_array_elements_text(CAST(chunk.embedding_vector AS jsonb))
                         WITH ORDINALITY AS chunk_part(component, position)
                      ON chunk_part.position = query_part.position
                ) scored
                WHERE chunk.document_id IN (%s)
                  AND chunk.embedding_dimensions = ?
                ORDER BY scored.score DESC, chunk.id
                LIMIT ?
                """.formatted(placeholders);
        List<Object> arguments = new ArrayList<>();
        arguments.add(json(queryVector));
        arguments.addAll(documentIds);
        arguments.add(queryVector.size());
        arguments.add(topK);
        return jdbcTemplate.query(sql, (result, row) -> new SimilarityMatch(
                result.getString("document_id"), result.getString("id"),
                result.getInt("sequence_number"), result.getString("content"),
                result.getDouble("score"), result.getString("embedding_model")),
                arguments.toArray());
    }

    @Override
    public void save(KnowledgeChunk chunk) {
        jdbcTemplate.update("""
                INSERT INTO platform_knowledge_chunks (
                    id, document_id, sequence_number, content, embedding_reference,
                    content_hash, embedding_model, embedding_dimensions, embedding_vector,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    document_id = EXCLUDED.document_id,
                    sequence_number = EXCLUDED.sequence_number,
                    content = EXCLUDED.content,
                    embedding_reference = EXCLUDED.embedding_reference,
                    content_hash = EXCLUDED.content_hash,
                    embedding_model = EXCLUDED.embedding_model,
                    embedding_dimensions = EXCLUDED.embedding_dimensions,
                    embedding_vector = EXCLUDED.embedding_vector
                """,
                chunk.id(), chunk.documentId(), chunk.sequence(), chunk.content(),
                chunk.embeddingReference(), chunk.contentHash(), chunk.embeddingModel(),
                chunk.embeddingDimensions(), json(chunk.embedding()), Timestamp.from(chunk.createdAt()));
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        jdbcTemplate.update("DELETE FROM platform_knowledge_chunks WHERE document_id = ?", documentId);
    }

    private KnowledgeChunk map(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeChunk(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getInt("sequence_number"),
                rs.getString("content"),
                rs.getString("embedding_reference"),
                rs.getString("content_hash"),
                rs.getString("embedding_model"),
                rs.getInt("embedding_dimensions"),
                parseVector(rs.getString("embedding_vector")),
                rs.getTimestamp("created_at").toInstant());
    }

    private String json(List<Double> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Knowledge embedding", exception);
        }
    }

    private List<Double> parseVector(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, DOUBLE_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to parse Knowledge embedding", exception);
        }
    }
}
