package com.spaceagent.platform.knowledge.infrastructure.persistence;
import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;

@Repository @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeIndexRecoveryRepository implements KnowledgeIndexRecoveryRepository {
    private final JdbcTemplate jdbc;private final KnowledgeIndexJobRepository jobs;private final TransactionTemplate tx;
    public PostgresKnowledgeIndexRecoveryRepository(JdbcTemplate jdbc,KnowledgeIndexJobRepository jobs){this.jdbc=jdbc;this.jobs=jobs;tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));}
    public Optional<KnowledgeIndexJob> claim(String id,long revision,String worker){return tx.execute(s->{
        int count=jdbc.update("UPDATE platform_knowledge_index_jobs SET state='RUNNING',worker=?,lease_until=clock_timestamp()+interval '90 seconds',fence=fence+1,revision=revision+1 WHERE id=? AND revision=? AND state='RECONCILIATION_REQUIRED'",worker,id,revision);
        return count==1?jobs.find(id):Optional.empty();
    });}
    public void resolve(KnowledgeIndexJob.Lease lease,KnowledgeIndexJob.Batch b){tx.executeWithoutResult(s->{
        if(!lease.jobId().equals(b.jobId()) || b.state()!=KnowledgeIndexJob.BatchState.COMPLETED && b.state()!=KnowledgeIndexJob.BatchState.REJECTED)throw new IllegalArgumentException("Invalid reconciliation");
        if(jdbc.queryForList("SELECT id FROM platform_knowledge_index_jobs WHERE id=? AND worker=? AND fence=? AND state='RUNNING' AND stage=? AND lease_until>clock_timestamp() FOR UPDATE",String.class,lease.jobId(),lease.worker(),lease.fence(),b.stage().name()).isEmpty())throw new IllegalStateException("Recovery lease changed");
        if(jdbc.update("UPDATE platform_knowledge_index_batches SET state=?,output_reference=?,output_hash=? WHERE job_id=? AND stage=? AND ordinal=? AND input_hash=? AND item_count=? AND state IN ('UNKNOWN','IN_FLIGHT')",b.state().name(),b.outputReference(),b.outputHash(),b.jobId(),b.stage().name(),b.ordinal(),b.inputHash(),b.itemCount())!=1)throw new IllegalStateException("Recovery batch changed");
    });}
    public void audit(String job,String actor,String outcome){jdbc.update("INSERT INTO platform_knowledge_index_reconciliations(job_id,actor_id,outcome) VALUES (?,?,?)",job,actor,outcome);}
}
