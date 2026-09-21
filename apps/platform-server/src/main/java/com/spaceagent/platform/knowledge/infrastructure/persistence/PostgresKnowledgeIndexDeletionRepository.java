package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeIndexDeletionRepository implements KnowledgeIndexDeletionRepository {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public PostgresKnowledgeIndexDeletionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));}
    public boolean reserved(String base,String doc){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_knowledge_index_tombstones WHERE base_id=? AND document_id=? AND kind='DOCUMENT')",Boolean.class,base,doc));}
    public View request(String base,String doc,long revision){return tx.execute(s->{
        if(jdbc.queryForList("SELECT id FROM platform_knowledge_bases WHERE id=? FOR UPDATE",String.class,base).isEmpty())throw new IllegalStateException("Base missing");
        var terminal=view(base,doc);if(terminal.isPresent() && terminal.get().state().equals("DELETED"))return terminal.get();
        var h=jdbc.queryForMap("SELECT revision,state FROM platform_knowledge_document_heads WHERE base_id=? AND document_id=? FOR UPDATE",base,doc);
        long current=((Number)h.get("revision")).longValue();
        if(!h.get("state").equals("ACTIVE")) {if(revision!=current && revision!=current-1)throw new IllegalStateException("Deletion revision changed");return view(base,doc).orElseThrow();}
        if(current!=revision)throw new IllegalStateException("Deletion revision changed");
        jdbc.update("UPDATE platform_knowledge_document_heads SET state='DELETING',revision=revision+1,active_generation_id=NULL WHERE document_id=?",doc);
        jdbc.update("""
            INSERT INTO platform_knowledge_index_tombstones(generation_id,base_id,document_id,job_id,request_tenant,request_actor,space_id,space_fingerprint,dimensions,storage_schema,scope_key,next_check_at)
            SELECT g.id,g.base_id,g.document_id,j.id,j.organization_id,j.requested_by,e.id,e.fingerprint,e.dimensions,COALESCE(j.storage_schema,'dense_v1'),
              CASE WHEN b.scope='PERSONAL' THEN 'user:'||b.owner_id ELSE 'org:'||b.organization_id END,
              GREATEST(clock_timestamp(),COALESCE(j.lease_until,clock_timestamp()))+interval '60 seconds'
            FROM platform_knowledge_index_generations g JOIN platform_knowledge_embedding_spaces e ON e.id=g.space_id
             JOIN platform_knowledge_bases b ON b.id=g.base_id LEFT JOIN platform_knowledge_index_jobs j ON j.generation_id=g.id
            WHERE g.document_id=? AND g.base_id=? ON CONFLICT(generation_id) DO UPDATE SET kind='DOCUMENT',state='PENDING',clean_passes=0,
              fence=platform_knowledge_index_tombstones.fence+1,claim_token=NULL,lease_until=NULL,next_check_at=EXCLUDED.next_check_at
            """,doc,base);
        jdbc.update("""
            UPDATE platform_knowledge_index_jobs SET state='CANCELLED',worker=NULL,lease_until=NULL,fence=fence+1,revision=revision+1,
              error_code='INDEX_DOCUMENT_DELETED',updated_at=clock_timestamp()
            WHERE generation_id IN (SELECT id FROM platform_knowledge_index_generations WHERE document_id=?) AND state<>'COMPLETED'
            """,doc);
        jdbc.update("UPDATE platform_knowledge_index_outbox SET pending=false WHERE job_id IN (SELECT job_id FROM platform_knowledge_index_tombstones WHERE document_id=?)",doc);
        jdbc.update("UPDATE platform_knowledge_documents SET status='DELETED',updated_at=clock_timestamp() WHERE id=?",doc);
        return view(base,doc).orElseThrow();
    });}
    public Optional<View> view(String base,String doc){return jdbc.query("""
        SELECT h.document_id,h.state,(SELECT count(*) FROM platform_knowledge_index_tombstones t WHERE t.document_id=h.document_id AND t.state<>'CLEAN') pending
        FROM platform_knowledge_document_heads h WHERE h.base_id=? AND h.document_id=?
        UNION ALL
        SELECT t.document_id,CASE WHEN bool_and(t.state='CLEAN') THEN 'DELETED' ELSE 'DELETING' END,count(*) FILTER(WHERE t.state<>'CLEAN')
        FROM platform_knowledge_index_tombstones t WHERE t.base_id=? AND t.document_id=?
          AND NOT EXISTS(SELECT 1 FROM platform_knowledge_document_heads h WHERE h.document_id=t.document_id) GROUP BY t.document_id
        """,(r,n)->new View(r.getString(1),r.getString(2),r.getLong(3)),base,doc,base,doc).stream().findFirst();}
    public Optional<Claim> claim(){String token=UUID.randomUUID().toString();return jdbc.query("""
        WITH candidate AS (SELECT generation_id FROM platform_knowledge_index_tombstones
          WHERE next_check_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp())
          ORDER BY next_check_at,generation_id LIMIT 1 FOR UPDATE SKIP LOCKED)
        UPDATE platform_knowledge_index_tombstones t SET claim_token=?,fence=fence+1,lease_until=clock_timestamp()+interval '90 seconds'
        FROM candidate WHERE t.generation_id=candidate.generation_id RETURNING t.*
        """,(r,n)->new Claim(r.getString("generation_id"),r.getString("base_id"),r.getString("document_id"),r.getString("job_id"),
                new VectorIndexGateway.Space(r.getString("space_id"),r.getString("space_fingerprint"),r.getInt("dimensions"),r.getString("storage_schema")),r.getString("scope_key"),r.getString("claim_token"),r.getLong("fence"),r.getString("request_tenant"),r.getString("request_actor"),r.getString("kind")),token).stream().findFirst();}
    public boolean finish(Claim c,boolean clean,String code){return Boolean.TRUE.equals(tx.execute(s->{
        int changed=jdbc.update("""
            UPDATE platform_knowledge_index_tombstones SET clean_passes=CASE WHEN ? THEN clean_passes+1 ELSE 0 END,
              state=CASE WHEN ? AND clean_passes>=1 THEN 'CLEAN' ELSE 'PENDING' END,
              next_check_at=clock_timestamp()+CASE WHEN ? AND clean_passes>=1 THEN interval '1 hour' ELSE interval '60 seconds' END,
              safe_code=?,claim_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
            WHERE generation_id=? AND claim_token=? AND fence=? AND lease_until>clock_timestamp()
            """,clean,clean,clean,code,c.generationId(),c.token(),c.fence());
        if(changed!=1)return false;
        if(c.kind().equals("GENERATION")){
            jdbc.update("DELETE FROM platform_knowledge_generation_chunks WHERE generation_id=? AND EXISTS(SELECT 1 FROM platform_knowledge_index_tombstones WHERE generation_id=? AND state='CLEAN' AND kind='GENERATION') AND NOT EXISTS(SELECT 1 FROM platform_knowledge_document_heads WHERE active_generation_id=?)",c.generationId(),c.generationId(),c.generationId());
            return true;
        }
        jdbc.update("""
            UPDATE platform_knowledge_document_heads SET state=CASE WHEN EXISTS(SELECT 1 FROM platform_knowledge_index_tombstones WHERE document_id=? AND state<>'CLEAN')
              THEN 'DELETING' ELSE 'DELETED' END WHERE document_id=? AND state<>'ACTIVE'
            """,c.documentId(),c.documentId());
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_knowledge_document_heads WHERE document_id=? AND state='DELETED')",Boolean.class,c.documentId())))purge(c.documentId());
        return true;
    }));}
    public boolean scopeCleanup(String user,String organization){return Boolean.TRUE.equals(tx.execute(s->{
        var docs=jdbc.query("""
            SELECT h.base_id,h.document_id,h.revision FROM platform_knowledge_document_heads h
              JOIN platform_knowledge_documents d ON d.id=h.document_id JOIN platform_knowledge_bases b ON b.id=h.base_id
            WHERE (CAST(? AS VARCHAR) IS NOT NULL AND d.owner_id=?) OR (CAST(? AS VARCHAR) IS NOT NULL AND b.organization_id=?) ORDER BY h.base_id,h.document_id LIMIT 100
            """,(r,n)->new Object[]{r.getString(1),r.getString(2),r.getLong(3)},user,user,organization,organization);
        boolean blocked=false;
        for(var row:docs){String base=(String)row[0],doc=(String)row[1];
            var state=request(base,doc,(Long)row[2]);if(!state.state().equals("DELETED")){blocked=true;continue;}
            purge(doc);
        }
        return !blocked && docs.size()<100;
    }));}
    @Override public int retireSuperseded(){
        return jdbc.update("""
            INSERT INTO platform_knowledge_index_tombstones(generation_id,base_id,document_id,job_id,request_tenant,request_actor,space_id,space_fingerprint,dimensions,storage_schema,scope_key,next_check_at,kind)
            SELECT g.id,g.base_id,g.document_id,j.id,j.organization_id,j.requested_by,e.id,e.fingerprint,e.dimensions,j.storage_schema,
              CASE WHEN b.scope='PERSONAL' THEN 'user:'||b.owner_id ELSE 'org:'||b.organization_id END,clock_timestamp()+interval '60 seconds','GENERATION'
            FROM platform_knowledge_index_generations g JOIN platform_knowledge_index_jobs j ON j.generation_id=g.id AND j.state='COMPLETED'
            JOIN platform_knowledge_embedding_spaces e ON e.id=g.space_id JOIN platform_knowledge_bases b ON b.id=g.base_id
            JOIN platform_knowledge_document_heads h ON h.document_id=g.document_id AND h.state='ACTIVE' AND h.active_generation_id IS NOT NULL AND h.active_generation_id<>g.id
            WHERE j.updated_at<clock_timestamp()-interval '24 hours' AND NOT EXISTS(SELECT 1 FROM platform_knowledge_index_tombstones t WHERE t.generation_id=g.id)
            ORDER BY j.updated_at,g.id LIMIT 64 ON CONFLICT DO NOTHING
            """);
    }
    private void purge(String doc){
        // Tombstones deliberately remain after metadata is purged, for late-write cleanup.
        jdbc.update("DELETE FROM platform_knowledge_index_intake_requests WHERE document_id=?",doc);
        String jobs="SELECT j.id FROM platform_knowledge_index_jobs j JOIN platform_knowledge_index_generations g ON g.id=j.generation_id WHERE g.document_id=?";
        for(String table:List.of("platform_knowledge_index_batches","platform_knowledge_index_manifests","platform_knowledge_index_outbox"))jdbc.update("DELETE FROM "+table+" WHERE job_id IN ("+jobs+")",doc);
        jdbc.update("DELETE FROM platform_knowledge_index_jobs WHERE id IN ("+jobs+")",doc);
        jdbc.update("DELETE FROM platform_knowledge_generation_chunks WHERE generation_id IN (SELECT id FROM platform_knowledge_index_generations WHERE document_id=?)",doc);
        jdbc.update("DELETE FROM platform_knowledge_generation_sources WHERE generation_id IN (SELECT id FROM platform_knowledge_index_generations WHERE document_id=?)",doc);
        jdbc.update("DELETE FROM platform_knowledge_document_heads WHERE document_id=?",doc);
        jdbc.update("DELETE FROM platform_knowledge_index_generations WHERE document_id=?",doc);
        jdbc.update("DELETE FROM platform_knowledge_documents WHERE id=?",doc);
    }
}
