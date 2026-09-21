package com.spaceagent.platform.knowledge.infrastructure.persistence;

import com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob;
import com.spaceagent.platform.knowledge.infrastructure.AbstractKnowledgeIndexJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresKnowledgeIndexJobRepository extends AbstractKnowledgeIndexJobRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public PostgresKnowledgeIndexJobRepository(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.tx=new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }
    @Override protected <T> T transaction(Supplier<T> work) { return tx.execute(status->work.get()); }
    @Override protected Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    @Override public KnowledgeIndexJob enqueue(KnowledgeIndexJob candidate) {
        return transaction(()->{
            var i=candidate.input();
            var job=KnowledgeIndexJob.queued(candidate.id(),i,now());
            jdbc.update("""
                    INSERT INTO platform_knowledge_index_jobs(id,base_id,generation_id,requested_by,organization_id,
                      idempotency_key,request_hash,storage_schema,max_attempts,stage,state,revision,fence,attempts,
                      next_attempt_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,'PARSING','QUEUED',1,0,0,?,?,?) ON CONFLICT DO NOTHING
                    """,job.id(),i.baseId(),i.generationId(),i.requestedBy(),i.organizationId(),i.idempotencyKey(),i.requestHash(),
                    i.storageSchema(),i.maxAttempts(),ts(job.createdAt()),ts(job.createdAt()),ts(job.createdAt()));
            var actual=jdbc.query("""
                    SELECT * FROM platform_knowledge_index_jobs WHERE base_id=? AND requested_by=? AND idempotency_key=? FOR UPDATE
                    """,this::job,i.baseId(),i.requestedBy(),i.idempotencyKey()).stream().findFirst()
                    .orElseThrow(()->new IllegalStateException("Generation already has an index job"));
            sameRequest(actual,job);
            jdbc.update("INSERT INTO platform_knowledge_index_outbox(job_id) VALUES (?) ON CONFLICT DO NOTHING",actual.id());
            return actual;
        });
    }
    @Override public Optional<KnowledgeIndexJob> find(String id) {
        return jdbc.query("SELECT * FROM platform_knowledge_index_jobs WHERE id=?",this::job,id).stream().findFirst();
    }
    @Override protected KnowledgeIndexJob lock(String id) {
        return jdbc.query("SELECT * FROM platform_knowledge_index_jobs WHERE id=? FOR UPDATE",this::job,id).stream()
                .findFirst().orElseThrow(()->new IllegalStateException("Index job missing"));
    }
    @Override public List<KnowledgeIndexJob> list(String base,int offset,int limit) {
        return jdbc.query("SELECT * FROM platform_knowledge_index_jobs WHERE base_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                this::job,base,limit,offset);
    }
    @Override public Optional<KnowledgeIndexJob> claimNext(String worker,int seconds) {
        leaseSeconds(seconds);
        return transaction(()->{
            jdbc.update("""
                INSERT INTO platform_knowledge_worker_fairness(tenant_key)
                SELECT DISTINCT COALESCE(j.organization_id,'user:'||j.requested_by) FROM platform_knowledge_index_jobs j
                WHERE j.state IN ('QUEUED','RETRY_WAIT','RUNNING') AND NOT EXISTS(
                    SELECT 1 FROM platform_knowledge_worker_fairness f WHERE f.tenant_key=COALESCE(j.organization_id,'user:'||j.requested_by))
                LIMIT 1000 ON CONFLICT DO NOTHING
                """);
            var candidate=jdbc.query("""
                    SELECT j.* FROM platform_knowledge_index_jobs j
                    JOIN platform_knowledge_index_outbox o ON o.job_id=j.id AND o.pending
                    JOIN platform_knowledge_worker_fairness f ON f.tenant_key=COALESCE(j.organization_id,'user:'||j.requested_by)
                    WHERE ((j.state IN ('QUEUED','RETRY_WAIT') AND j.next_attempt_at<=clock_timestamp())
                       OR (j.state='RUNNING' AND j.lease_until<=clock_timestamp()))
                    AND NOT EXISTS(SELECT 1 FROM platform_knowledge_index_jobs active WHERE active.state='RUNNING'
                        AND active.lease_until>clock_timestamp() AND active.id<>j.id
                        AND COALESCE(active.organization_id,'user:'||active.requested_by)=f.tenant_key)
                    ORDER BY f.last_claim_at,j.next_attempt_at,j.id LIMIT 1 FOR UPDATE OF j,f SKIP LOCKED
                    """,this::job).stream().findFirst();
            if(candidate.isEmpty()) return Optional.empty();
            var claimed=claim(candidate.get(),worker,seconds);
            jdbc.update("UPDATE platform_knowledge_worker_fairness SET last_claim_at=clock_timestamp() WHERE tenant_key=?",
                claimed.input().organizationId()==null?"user:"+claimed.input().requestedBy():claimed.input().organizationId());
            jdbc.update("UPDATE platform_knowledge_index_outbox SET deliveries=deliveries+1,updated_at=clock_timestamp() WHERE job_id=?",claimed.id());
            return claimed.progress().state()==State.RUNNING?Optional.of(claimed):Optional.empty();
        });
    }
    @Override protected void save(KnowledgeIndexJob j) {
        var p=j.progress();
        jdbc.update("""
                UPDATE platform_knowledge_index_jobs SET stage=?,state=?,revision=?,fence=?,worker=?,lease_until=?,
                  attempts=?,next_attempt_at=?,error_code=?,updated_at=? WHERE id=?
                """,p.stage().name(),p.state().name(),p.revision(),p.fence(),p.worker(),ts(p.leaseUntil()),p.attempts(),
                ts(p.nextAttemptAt()),p.errorCode(),ts(j.updatedAt()),j.id());
        jdbc.update("UPDATE platform_knowledge_index_outbox SET pending=?,updated_at=clock_timestamp() WHERE job_id=?",pending(j),j.id());
    }
    @Override public List<Batch> batches(String id,Stage stage,int offset,int limit) {
        // offset is an ordinal cursor; manifests are contiguous when advancing a stage.
        return jdbc.query("""
                SELECT * FROM platform_knowledge_index_batches WHERE job_id=? AND stage=? AND ordinal>=? ORDER BY ordinal LIMIT ?
                """,(r,n)->new Batch(r.getString("job_id"),Stage.valueOf(r.getString("stage")),r.getInt("ordinal"),
                r.getString("input_hash"),r.getInt("item_count"),BatchState.valueOf(r.getString("state")),
                r.getString("output_reference"),r.getString("output_hash")),id,stage.name(),offset,limit);
    }
    @Override protected void putBatch(Batch b) {
        jdbc.update("""
                INSERT INTO platform_knowledge_index_batches(job_id,stage,ordinal,input_hash,item_count,state,output_reference,output_hash)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(job_id,stage,ordinal) DO UPDATE
                SET state=EXCLUDED.state,output_reference=EXCLUDED.output_reference,output_hash=EXCLUDED.output_hash
                """,b.jobId(),b.stage().name(),b.ordinal(),b.inputHash(),b.itemCount(),b.state().name(),b.outputReference(),b.outputHash());
    }
    @Override public Optional<Manifest> manifest(String id,Stage stage) {
        return jdbc.query("SELECT * FROM platform_knowledge_index_manifests WHERE job_id=? AND stage=?",
                (r,n)->new Manifest(r.getString("job_id"),Stage.valueOf(r.getString("stage")),r.getString("input_hash"),
                        r.getInt("batch_count")),id,stage.name()).stream().findFirst();
    }
    @Override protected void putManifest(Manifest m) {
        jdbc.update("""
                INSERT INTO platform_knowledge_index_manifests(job_id,stage,input_hash,batch_count)
                VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                """,m.jobId(),m.stage().name(),m.inputHash(),m.batchCount());
    }
    private KnowledgeIndexJob job(ResultSet r,int n) throws SQLException {
        return new KnowledgeIndexJob(r.getString("id"),new Input(r.getString("base_id"),r.getString("generation_id"),
                r.getString("requested_by"),r.getString("organization_id"),r.getString("idempotency_key"),r.getString("request_hash"),
                r.getString("storage_schema"),r.getInt("max_attempts")),new Progress(Stage.valueOf(r.getString("stage")),
                State.valueOf(r.getString("state")),r.getLong("revision"),r.getLong("fence"),r.getString("worker"),
                instant(r.getTimestamp("lease_until")),r.getInt("attempts"),instant(r.getTimestamp("next_attempt_at")),r.getString("error_code")),
                instant(r.getTimestamp("created_at")),instant(r.getTimestamp("updated_at")));
    }
    private static Timestamp ts(Instant v) { return v==null?null:Timestamp.from(v); }
    private static Instant instant(Timestamp v) { return v==null?null:v.toInstant(); }
}
