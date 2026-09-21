package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.KnowledgeIndexIntakeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeIndexIntakeRepository implements KnowledgeIndexIntakeRepository {
    private final JdbcTemplate jdbc;
    public PostgresKnowledgeIndexIntakeRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Receipt> find(String base,String actor,String key){
        return jdbc.query("SELECT * FROM platform_knowledge_index_intake_requests WHERE base_id=? AND actor_id=? AND idempotency_key=?",
                (r,n)->new Receipt(r.getString("base_id"),r.getString("actor_id"),r.getString("idempotency_key"),r.getString("request_hash"),
                        r.getString("document_id"),r.getString("generation_id"),r.getString("job_id"),r.getLong("document_revision"),
                        r.getString("source_kind"),r.getString("source_id"),r.getString("media_type"),r.getString("original_hash"),r.getString("original_reference"),r.getString("source_charset"),r.getString("normalization_version"),r.getLong("original_bytes")),base,actor,key).stream().findFirst();
    }
    public void insert(Receipt r){
        jdbc.update("""
            INSERT INTO platform_knowledge_index_intake_requests(base_id,actor_id,idempotency_key,request_hash,document_id,generation_id,
              job_id,document_revision,source_kind,source_id,media_type,original_hash,original_reference,source_charset,normalization_version,original_bytes) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,r.baseId(),r.actorId(),r.key(),r.requestHash(),r.documentId(),r.generationId(),r.jobId(),r.documentRevision(),r.sourceKind(),r.sourceId(),r.mediaType(),r.originalHash(),r.originalReference(),r.charset(),r.normalizationVersion(),r.originalBytes());
    }
    public void linkLegacy(String generation,String source){
        if(jdbc.update("UPDATE platform_knowledge_index_intake_requests SET legacy_source_document_id=? WHERE generation_id=? AND (legacy_source_document_id IS NULL OR legacy_source_document_id=?)",source,generation,source)!=1)throw new IllegalStateException("Legacy migration link conflict");
    }
    public Optional<Receipt> latestLegacyCopy(String actor,String source){
        var rows=jdbc.queryForList("SELECT base_id,idempotency_key FROM platform_knowledge_index_intake_requests WHERE actor_id=? AND legacy_source_document_id=? ORDER BY created_at DESC LIMIT 1",actor,source);
        return rows.isEmpty()?Optional.empty():find((String)rows.getFirst().get("base_id"),actor,(String)rows.getFirst().get("idempotency_key"));
    }
}
