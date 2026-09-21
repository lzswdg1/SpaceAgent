package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.sql.ResultSet;
import java.sql.SQLException;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeIndexPublicationRepository implements KnowledgeIndexPublicationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public PostgresKnowledgeIndexPublicationRepository(JdbcTemplate jdbc) {
        this.jdbc=jdbc; tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    @Override public Source registerSource(String generation,long expectedRevision,String reference) {
        return registerSource(generation,expectedRevision,reference,"text/plain","UTF-8");
    }
    @Override public Source registerSource(String generation,long expectedRevision,String reference,String media,String charset) {
        return registerSource(generation,expectedRevision,reference,media,charset,KnowledgeProcessingPolicy.legacy());
    }
    @Override public Source registerSource(String generation,long expectedRevision,String reference,String media,String charset,KnowledgeProcessingPolicy policy) {
        return tx.execute(s->{
            var gen=new PostgresKnowledgeIndexCatalogRepository(jdbc).findGeneration(
                    jdbc.queryForObject("SELECT base_id FROM platform_knowledge_index_generations WHERE id=?",String.class,generation),generation).orElseThrow();
            lockBase(gen.baseId());
            if(reference==null || !reference.equals("knowledge-index/"+gen.documentId()+"/"+gen.contentHash()))
                throw new IllegalArgumentException("Source reference must be content-addressed under its document");
            var existing=source(generation);
            if(existing.isPresent()) {
                if(existing.get().documentRevision()!=expectedRevision+1 || !existing.get().sourceReference().equals(reference)
                        || !existing.get().mediaType().equals(media) || !existing.get().charset().equals(charset) || !existing.get().policy().equals(policy))
                    throw new IllegalStateException("Source registration conflicts with the original revision");
                return existing.get();
            }
            var previous=lockedHead(gen.baseId(),gen.documentId());
            if(previous.isEmpty() && expectedRevision!=0 || previous.isPresent()
                    && (previous.get().revision()!=expectedRevision || !previous.get().state().equals("ACTIVE")))
                throw new IllegalStateException("Document revision or deletion state changed");
            jdbc.update("""
                INSERT INTO platform_knowledge_document_heads(document_id,base_id,revision,state,content_hash)
                VALUES (?,?,?,'ACTIVE',?) ON CONFLICT(document_id) DO UPDATE SET revision=EXCLUDED.revision,content_hash=EXCLUDED.content_hash
                """,gen.documentId(),gen.baseId(),expectedRevision+1,gen.contentHash());
            jdbc.update("INSERT INTO platform_knowledge_generation_sources(generation_id,document_revision,source_reference,media_type,source_charset,chunking_policy) VALUES (?,?,?,?,?,CAST(? AS JSONB))",
                    generation,expectedRevision+1,reference,media,charset,writeJson(policy));
            return source(generation).orElseThrow();
        });
    }
    @Override public Optional<Source> source(String generation) {
        return jdbc.query("""
                SELECT g.id,g.base_id,g.document_id,s.document_revision,g.content_hash,s.source_reference,s.media_type,s.source_charset,s.parse_metadata_reference,s.parse_metadata_hash,s.chunking_policy::text
                FROM platform_knowledge_generation_sources s JOIN platform_knowledge_index_generations g ON g.id=s.generation_id WHERE g.id=?
                """,(r,n)->new Source(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),policy(r.getString(11))),generation).stream().findFirst();
    }
    @Override public void saveParsingMetadata(KnowledgeIndexJob.Lease lease,String ref,String hash){tx.executeWithoutResult(s->{
        String generation=lockLease(lease,"PARSING");
        if(ref==null || !ref.equals("knowledge-index/"+lease.jobId()+"/"+hash) || !hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid parse metadata reference");
        if(jdbc.update("UPDATE platform_knowledge_generation_sources SET parse_metadata_reference=?,parse_metadata_hash=? WHERE generation_id=? AND (parse_metadata_hash IS NULL OR parse_metadata_hash=?)",ref,hash,generation,hash)!=1)
            throw new IllegalStateException("Immutable parsing evidence changed");
    });}
    @Override public Optional<Head> head(String base,String doc) {
        return jdbc.query("SELECT * FROM platform_knowledge_document_heads WHERE base_id=? AND document_id=?",this::mapHead,base,doc).stream().findFirst();
    }
    private Optional<Head> lockedHead(String base,String doc) {
        return jdbc.query("SELECT * FROM platform_knowledge_document_heads WHERE base_id=? AND document_id=? FOR UPDATE",this::mapHead,base,doc).stream().findFirst();
    }
    private void lockBase(String base) {
        if(jdbc.queryForList("SELECT id FROM platform_knowledge_bases WHERE id=? AND state='ACTIVE' FOR UPDATE",String.class,base).isEmpty())
            throw new IllegalStateException("Knowledge base is not active");
    }
    private String lockLease(KnowledgeIndexJob.Lease lease,String stage) {
        return jdbc.query("""
            SELECT generation_id FROM platform_knowledge_index_jobs WHERE id=? AND worker=? AND fence=?
            AND state='RUNNING' AND stage=? AND lease_until>clock_timestamp() FOR UPDATE
            """,(r,n)->r.getString(1),lease.jobId(),lease.worker(),lease.fence(),stage).stream().findFirst()
                .orElseThrow(()->new IllegalStateException("Publication lease changed"));
    }
    @Override public void saveChunks(KnowledgeIndexJob.Lease lease,List<Chunk> chunks) {
        if(chunks==null || chunks.isEmpty() || chunks.size()>32768) throw new IllegalArgumentException("Invalid chunk count");
        long characters=0; Set<String> ids=new HashSet<>();
        for(int i=0;i<chunks.size();i++) {
            var c=chunks.get(i); characters+=c.content().length();
            if(c.ordinal()!=i || !ids.add(c.id()) || !hash(c.content()).equals(c.contentHash())) throw new IllegalArgumentException("Chunk manifest is invalid");
        }
        if(characters>10_000_000) throw new IllegalArgumentException("Chunk manifest exceeds bounds");
        tx.executeWithoutResult(s->{
            String generation=lockLease(lease,"CHUNKING");
            var previous=chunks(generation);
            if(!previous.isEmpty()) {if(!previous.equals(chunks))throw new IllegalStateException("Immutable chunk manifest conflict");return;}
            jdbc.batchUpdate("INSERT INTO platform_knowledge_generation_chunks(generation_id,chunk_id,ordinal,content,content_hash,metadata) VALUES (?,?,?,?,?,CAST(? AS JSONB))",
                    chunks,64,(p,c)->{p.setString(1,generation);p.setString(2,c.id());p.setInt(3,c.ordinal());p.setString(4,c.content());p.setString(5,c.contentHash());p.setString(6,writeJson(c.metadata()));});
        });
    }
    @Override public List<Chunk> chunks(String generation) {
        return jdbc.query("SELECT chunk_id,ordinal,content,content_hash,metadata::text FROM platform_knowledge_generation_chunks WHERE generation_id=? ORDER BY ordinal",
                (r,n)->new Chunk(r.getString(1),r.getInt(2),r.getString(3),r.getString(4),readMap(r.getString(5))),generation);
    }
    @Override public Head activate(KnowledgeIndexJob.Lease lease) {
        return tx.execute(s->{
            String generation=jdbc.queryForObject("SELECT generation_id FROM platform_knowledge_index_jobs WHERE id=?",String.class,lease.jobId());
            var input=source(generation).orElseThrow();lockBase(input.baseId());
            var head=lockedHead(input.baseId(),input.documentId()).orElseThrow();
            if(!head.state().equals("ACTIVE") || head.revision()!=input.documentRevision() || !head.contentHash().equals(input.contentHash()))
                throw new IllegalStateException("Document changed during indexing");
            lockLease(lease,"READY_TO_ACTIVATE");
            Integer expected=jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_generation_chunks WHERE generation_id=?",Integer.class,generation);
            if(expected==null || expected<1) throw new IllegalStateException("Missing authoritative chunks");
            for(String stage:List.of("EMBEDDING","WRITING","VERIFYING")) {
                Boolean complete=jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM platform_knowledge_index_manifests m
                    WHERE m.job_id=? AND m.stage=? AND m.batch_count=(SELECT count(*) FROM platform_knowledge_index_batches b
                     WHERE b.job_id=m.job_id AND b.stage=m.stage AND b.state='COMPLETED')
                    AND ?=(SELECT COALESCE(sum(b.item_count),0) FROM platform_knowledge_index_batches b WHERE b.job_id=m.job_id AND b.stage=m.stage))
                    """,Boolean.class,lease.jobId(),stage,expected);
                if(!Boolean.TRUE.equals(complete)) throw new IllegalStateException("Incomplete publication receipts");
            }
            Boolean linked=jdbc.queryForObject("""
                SELECT NOT EXISTS(SELECT 1 FROM platform_knowledge_index_batches e
                  LEFT JOIN platform_knowledge_index_batches w ON w.job_id=e.job_id AND w.ordinal=e.ordinal AND w.stage='WRITING'
                  LEFT JOIN platform_knowledge_index_batches v ON v.job_id=e.job_id AND v.ordinal=e.ordinal AND v.stage='VERIFYING'
                  WHERE e.job_id=? AND e.stage='EMBEDDING' AND (w.input_hash IS DISTINCT FROM e.output_hash
                    OR w.output_hash IS DISTINCT FROM e.output_hash OR v.input_hash IS DISTINCT FROM e.output_hash
                    OR v.output_hash IS DISTINCT FROM e.output_hash OR w.item_count IS DISTINCT FROM e.item_count
                    OR v.item_count IS DISTINCT FROM e.item_count))
                """,Boolean.class,lease.jobId());
            if(!Boolean.TRUE.equals(linked)) throw new IllegalStateException("Publication receipt lineage mismatch");
            jdbc.update("UPDATE platform_knowledge_document_heads SET active_generation_id=? WHERE document_id=?",generation,input.documentId());
            jdbc.update("UPDATE platform_knowledge_index_jobs SET state='COMPLETED',worker=NULL,lease_until=NULL,fence=fence+1,revision=revision+1,updated_at=clock_timestamp() WHERE id=?",lease.jobId());
            jdbc.update("UPDATE platform_knowledge_index_outbox SET pending=false,updated_at=clock_timestamp() WHERE job_id=?",lease.jobId());
            return head(input.baseId(),input.documentId()).orElseThrow();
        });
    }
    @Override public Head invalidate(String base,String doc,long revision) {
        return tx.execute(s->{
            lockBase(base);var head=lockedHead(base,doc).orElseThrow();
            if(head.revision()!=revision) throw new IllegalStateException("Document revision changed");
            jdbc.update("UPDATE platform_knowledge_document_heads SET state='DELETING',revision=revision+1,active_generation_id=NULL WHERE document_id=?",doc);
            return head(base,doc).orElseThrow();
        });
    }
    private Head mapHead(ResultSet r,int n) throws SQLException {
        return new Head(r.getString("base_id"),r.getString("document_id"),r.getLong("revision"),r.getString("state"),r.getString("content_hash"),r.getString("active_generation_id"));
    }
    private static String hash(String text) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String writeJson(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static Map<String,Object> readMap(String value){try{return JSON.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    private static KnowledgeProcessingPolicy policy(String value){var m=readMap(value);return new KnowledgeProcessingPolicy(KnowledgeProcessingPolicy.Strategy.valueOf(m.get("strategy").toString()),((Number)m.get("size")).intValue(),((Number)m.get("overlap")).intValue());}
}
