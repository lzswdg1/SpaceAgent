package com.spaceagent.platform.knowledge.infrastructure.persistence;
import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeProcessingPolicyRepository implements KnowledgeProcessingPolicyRepository {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public PostgresKnowledgeProcessingPolicyRepository(JdbcTemplate jdbc){this.jdbc=jdbc;tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));}
    public Snapshot get(String base){return jdbc.query("SELECT * FROM platform_knowledge_processing_policies WHERE base_id=?",(r,n)->new Snapshot(r.getLong("revision"),new KnowledgeProcessingPolicy(KnowledgeProcessingPolicy.Strategy.valueOf(r.getString("strategy")),r.getInt("segment_size"),r.getInt("overlap_size"))),base).stream().findFirst().orElse(new Snapshot(0,KnowledgeProcessingPolicy.defaults()));}
    public Snapshot save(String base,long revision,KnowledgeProcessingPolicy p){return tx.execute(s->{
        jdbc.queryForObject("SELECT id FROM platform_knowledge_bases WHERE id=? FOR UPDATE",String.class,base);var prior=get(base);
        if(prior.revision()!=revision)throw new IllegalStateException("Processing policy changed");
        jdbc.update("INSERT INTO platform_knowledge_processing_policies(base_id,revision,strategy,segment_size,overlap_size) VALUES (?,?,?,?,?) ON CONFLICT(base_id) DO UPDATE SET revision=EXCLUDED.revision,strategy=EXCLUDED.strategy,segment_size=EXCLUDED.segment_size,overlap_size=EXCLUDED.overlap_size",base,revision+1,p.strategy().name(),p.size(),p.overlap());return get(base);
    });}
}
