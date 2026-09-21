package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.knowledge.domain.KnowledgeRetrievalIndexRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeRetrievalIndexRepository implements KnowledgeRetrievalIndexRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public PostgresKnowledgeRetrievalIndexRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    @Override public List<Published> published(String base,int limit){
        if(limit<1||limit>513)throw new IllegalArgumentException("Invalid retrieval bound");
        return jdbc.query("""
            SELECT h.document_id,g.id,g.space_id,j.storage_schema FROM platform_knowledge_document_heads h
            JOIN platform_knowledge_index_generations g ON g.id=h.active_generation_id AND g.base_id=h.base_id
            JOIN platform_knowledge_index_jobs j ON j.generation_id=g.id AND j.state='COMPLETED'
            JOIN platform_knowledge_documents d ON d.id=h.document_id AND d.status<>'DELETED'
            WHERE h.base_id=? AND h.state='ACTIVE' ORDER BY h.document_id LIMIT ?
            """,(rs,n)->new Published(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),base,limit);
    }
    @Override public Optional<Evidence> evidence(String base,String generation,String chunk,String hash){
        return jdbc.query("""
            SELECT d.id,d.name,c.generation_id,c.chunk_id,c.ordinal,c.content,c.content_hash,s.document_revision,c.metadata
            FROM platform_knowledge_document_heads h
            JOIN platform_knowledge_documents d ON d.id=h.document_id AND d.status<>'DELETED'
            JOIN platform_knowledge_document_scopes ds ON ds.document_id=d.id AND ds.base_id=h.base_id
            JOIN platform_knowledge_generation_sources s ON s.generation_id=h.active_generation_id
            JOIN platform_knowledge_generation_chunks c ON c.generation_id=s.generation_id
            WHERE h.base_id=? AND h.state='ACTIVE' AND h.active_generation_id=? AND c.chunk_id=? AND c.content_hash=?
            """,(rs,n)->{
                try{return new Evidence(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),
                        rs.getString(6),rs.getString(7),rs.getLong(8),json.readValue(rs.getString(9),new TypeReference<Map<String,Object>>(){}));}
                catch(Exception e){throw new IllegalStateException("Invalid retrieval evidence metadata");}
            },base,generation,chunk,hash).stream().findFirst();
    }

    @Override
    public Map<EvidenceKey, Evidence> evidenceBatch(List<EvidenceKey> requests) {
        var keys = List.copyOf(new LinkedHashSet<>(requests));
        if (keys.size() > 100) throw new IllegalArgumentException("Evidence batch exceeds bounds");
        if (keys.isEmpty()) return Map.of();
        String values = String.join(",", Collections.nCopies(keys.size(), "(?::varchar,?::varchar,?::varchar,?::varchar)"));
        List<Object> args = new ArrayList<>();
        keys.forEach(k -> Collections.addAll(args, k.baseId(), k.generationId(), k.chunkId(), k.contentHash()));
        String sql = "WITH requested(base_id,generation_id,chunk_id,content_hash) AS (VALUES " + values + ") " + """
            SELECT requested.base_id,d.id,d.name,c.generation_id,c.chunk_id,c.ordinal,c.content,c.content_hash,s.document_revision,c.metadata
            FROM requested
            JOIN platform_knowledge_document_heads h ON h.base_id=requested.base_id AND h.active_generation_id=requested.generation_id AND h.state='ACTIVE'
            JOIN platform_knowledge_documents d ON d.id=h.document_id AND d.status<>'DELETED'
            JOIN platform_knowledge_document_scopes ds ON ds.document_id=d.id AND ds.base_id=h.base_id
            JOIN platform_knowledge_generation_sources s ON s.generation_id=h.active_generation_id
            JOIN platform_knowledge_generation_chunks c ON c.generation_id=s.generation_id
              AND c.chunk_id=requested.chunk_id AND c.content_hash=requested.content_hash
            """;
        Map<EvidenceKey, Evidence> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            try {
                var key = new EvidenceKey(rs.getString(1), rs.getString(4), rs.getString(5), rs.getString(8));
                result.put(key, new Evidence(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6),
                        rs.getString(7), rs.getString(8), rs.getLong(9), json.readValue(rs.getString(10), new TypeReference<Map<String,Object>>() {})));
            } catch (Exception e) { throw new IllegalStateException("Invalid retrieval evidence metadata"); }
        }, args.toArray());
        return result;
    }
}
