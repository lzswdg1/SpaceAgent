package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.KnowledgeOperationalRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeOperationalRepository implements KnowledgeOperationalRepository {
    private final JdbcTemplate jdbc;public PostgresKnowledgeOperationalRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override @Transactional public void admit(String base,String tenant,boolean newDocument,long bytes,int maxDocs,int maxPending,long maxBytes){
        lock(tenant);
        long docs=jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_document_heads WHERE base_id=? AND state='ACTIVE'",Long.class,base);
        long pending=jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_jobs WHERE organization_id=? AND state NOT IN ('COMPLETED','CANCELLED','FAILED')",Long.class,tenant);
        long stored=jdbc.queryForObject("SELECT COALESCE(sum(r.original_bytes),0) FROM platform_knowledge_index_intake_requests r JOIN platform_knowledge_index_jobs j ON j.id=r.job_id WHERE j.organization_id=?",Long.class,tenant);
        if(newDocument && docs>=maxDocs || pending>=maxPending || stored+bytes>maxBytes)throw new IllegalArgumentException("KNOWLEDGE_INTAKE_QUOTA_EXCEEDED");
    }
    @Override @Transactional public Optional<String> acquireQuery(String tenant,int limit){
        lock(tenant);jdbc.update("DELETE FROM platform_knowledge_query_leases WHERE organization_id=? AND lease_until<=clock_timestamp()",tenant);
        if(jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_query_leases WHERE organization_id=?",Long.class,tenant)>=limit)return Optional.empty();
        String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO platform_knowledge_query_leases VALUES (?,?,clock_timestamp()+interval '10 minutes')",id,tenant);return Optional.of(id);
    }
    @Override public void releaseQuery(String id){jdbc.update("DELETE FROM platform_knowledge_query_leases WHERE id=?",id);}
    @Override public void expireQueries(){jdbc.update("DELETE FROM platform_knowledge_query_leases WHERE id IN (SELECT id FROM platform_knowledge_query_leases WHERE lease_until<=clock_timestamp() LIMIT 1000)");}
    private void lock(String tenant){jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,7914))",r->{},tenant);}
    @Override public Map<String,Long> statistics(){
        Map<String,Long> result=new LinkedHashMap<>();
        jdbc.query("SELECT state,count(*) count FROM platform_knowledge_index_jobs GROUP BY state",r->{result.put("jobs."+r.getString("state"),r.getLong("count"));});
        result.put("oldestPendingSeconds",jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(created_at))::bigint,0) FROM platform_knowledge_index_jobs WHERE state IN ('QUEUED','RETRY_WAIT')",Long.class));
        result.put("deletionsPending",jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_tombstones WHERE state<>'CLEAN'",Long.class));
        result.put("sourceBytes",jdbc.queryForObject("SELECT COALESCE(sum(original_bytes),0) FROM platform_knowledge_index_intake_requests",Long.class));
        result.put("legacySizeUnknown",jdbc.queryForObject("SELECT count(*) FROM platform_knowledge_index_intake_requests WHERE original_bytes=0",Long.class));
        return result;
    }
}
