package com.spaceagent.platform.knowledge.infrastructure.persistence;
import com.spaceagent.platform.knowledge.domain.KnowledgeIndexRepairRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.sql.*;

@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeIndexRepairRepository implements KnowledgeIndexRepairRepository {
    private final JdbcTemplate jdbc;public PostgresKnowledgeIndexRepairRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override @Transactional public Repair enqueue(String base,String document,String generation,String tenant,String actor,String key){
        jdbc.queryForObject("SELECT id FROM platform_knowledge_bases WHERE id=? FOR UPDATE",String.class,base);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,7914))",r->{},tenant);
        var prior=jdbc.query("SELECT * FROM platform_knowledge_index_repairs WHERE base_id=? AND actor_id=? AND idempotency_key=?",this::row,base,actor,key);
        if(!prior.isEmpty()){if(!prior.getFirst().generationId().equals(generation)||!prior.getFirst().documentId().equals(document))throw new IllegalStateException("Repair key conflict");return prior.getFirst();}
        if(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_repairs WHERE organization_id=? AND state IN ('PENDING','RUNNING')",Long.class,tenant)>=16)throw new IllegalStateException("Repair queue full");
        String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO platform_knowledge_index_repairs(id,base_id,document_id,generation_id,organization_id,actor_id,idempotency_key,state) VALUES (?,?,?,?,?,?,?,'PENDING')",id,base,document,generation,tenant,actor,key);return find(id).orElseThrow();
    }
    public Optional<Repair> find(String id){return jdbc.query("SELECT * FROM platform_knowledge_index_repairs WHERE id=?",this::row,id).stream().findFirst();}
    public Optional<String> completedIndexJob(String generation){return jdbc.queryForList("SELECT id FROM platform_knowledge_index_jobs WHERE generation_id=? AND state='COMPLETED'",String.class,generation).stream().findFirst();}
    public Optional<Repair> claim(){return jdbc.query("""
        WITH next AS(SELECT id FROM platform_knowledge_index_repairs WHERE state='PENDING' OR state='RUNNING' AND lease_until<=clock_timestamp()
            ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
        UPDATE platform_knowledge_index_repairs r SET state='RUNNING',claim_token=?,fence=fence+1,lease_until=clock_timestamp()+interval '90 seconds',updated_at=clock_timestamp()
        FROM next WHERE r.id=next.id RETURNING r.*
        """,this::row,UUID.randomUUID().toString()).stream().findFirst();}
    public boolean renew(Repair r){return jdbc.update("UPDATE platform_knowledge_index_repairs SET lease_until=clock_timestamp()+interval '90 seconds' WHERE id=? AND claim_token=? AND fence=? AND state='RUNNING' AND lease_until>clock_timestamp()",r.id(),r.token(),r.fence())==1;}
    public boolean finish(Repair r,String error){return jdbc.update("UPDATE platform_knowledge_index_repairs SET state=?,safe_code=?,claim_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=? AND claim_token=? AND fence=? AND state='RUNNING' AND lease_until>clock_timestamp()",error==null?"COMPLETED":"FAILED",error,r.id(),r.token(),r.fence())==1;}
    private Repair row(ResultSet r,int n)throws SQLException{return new Repair(r.getString("id"),r.getString("base_id"),r.getString("document_id"),r.getString("generation_id"),r.getString("organization_id"),r.getString("actor_id"),r.getString("state"),r.getString("claim_token"),r.getLong("fence"),r.getString("safe_code"));}
}
