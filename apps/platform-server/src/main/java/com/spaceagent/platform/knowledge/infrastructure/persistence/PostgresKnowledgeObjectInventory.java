package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeObjectInventory implements KnowledgeObjectInventory {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    public PostgresKnowledgeObjectInventory(JdbcTemplate jdbc){this.jdbc=jdbc;tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    public void record(String ref,String owner){tx.executeWithoutResult(s->jdbc.update("INSERT INTO platform_knowledge_object_inventory(reference,owner_id) VALUES (?,?) ON CONFLICT(reference) DO UPDATE SET last_seen_at=clock_timestamp()",ref,owner));}
    public void lockOwner(String owner){jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,7912))",r->{},owner);}
    public List<String> orphanCandidates(int limit){return jdbc.queryForList("SELECT reference FROM platform_knowledge_object_inventory WHERE last_seen_at<clock_timestamp()-interval '24 hours' ORDER BY last_seen_at,reference LIMIT ?",String.class,Math.max(1,Math.min(limit,256)));}
    public boolean deleteIfOrphan(String ref,KnowledgeIndexObjectStore objects){return Boolean.TRUE.equals(tx.execute(s->{
        var owners=jdbc.queryForList("SELECT owner_id FROM platform_knowledge_object_inventory WHERE reference=?",String.class,ref);
        if(owners.isEmpty())return false;lockOwner(owners.getFirst());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_knowledge_index_jobs WHERE id=?)",Boolean.class,owners.getFirst())))objects.deleteStalePartials(owners.getFirst());
        Boolean unused=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM platform_knowledge_object_inventory i WHERE reference=? AND last_seen_at<clock_timestamp()-interval '24 hours'
             AND NOT EXISTS(SELECT 1 FROM platform_knowledge_index_jobs j WHERE j.id=i.owner_id)
             AND NOT EXISTS(SELECT 1 FROM platform_knowledge_generation_sources s WHERE s.source_reference=i.reference OR s.parse_metadata_reference=i.reference)
             AND NOT EXISTS(SELECT 1 FROM platform_knowledge_index_intake_requests r WHERE r.original_reference=i.reference)
             AND NOT EXISTS(SELECT 1 FROM platform_knowledge_index_batches b WHERE b.output_reference=i.reference))
            """,Boolean.class,ref);
        if(!Boolean.TRUE.equals(unused)){jdbc.update("UPDATE platform_knowledge_object_inventory SET last_seen_at=clock_timestamp() WHERE reference=?",ref);return false;}
        objects.deleteReference(ref);jdbc.update("DELETE FROM platform_knowledge_object_inventory WHERE reference=?",ref);return true;
    }));}
}
