package com.spaceagent.platform.knowledge.infrastructure.memory;

import com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob;
import com.spaceagent.platform.knowledge.infrastructure.AbstractKnowledgeIndexJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;

@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="memory",matchIfMissing=true)
public class InMemoryKnowledgeIndexJobRepository extends AbstractKnowledgeIndexJobRepository {
    private final Map<String,KnowledgeIndexJob> jobs=new LinkedHashMap<>();
    private final Map<String,Batch> manifests=new HashMap<>();
    private final Map<String,Manifest> stages=new HashMap<>();
    private final Clock clock;
    public InMemoryKnowledgeIndexJobRepository() { this(Clock.systemUTC()); }
    public InMemoryKnowledgeIndexJobRepository(Clock clock) { this.clock=clock; }
    @Override protected synchronized <T> T transaction(Supplier<T> work) { return work.get(); }
    @Override protected Instant now() { return clock.instant(); }
    @Override protected KnowledgeIndexJob lock(String id) { return find(id).orElseThrow(()->new IllegalStateException("Index job missing")); }
    @Override public synchronized Optional<KnowledgeIndexJob> find(String id) { return Optional.ofNullable(jobs.get(id)); }
    @Override protected void save(KnowledgeIndexJob job) { jobs.put(job.id(),job); }
    @Override public synchronized KnowledgeIndexJob enqueue(KnowledgeIndexJob job) {
        var old=jobs.values().stream().filter(v->v.input().generationId().equals(job.input().generationId())
                || v.input().baseId().equals(job.input().baseId()) && v.input().requestedBy().equals(job.input().requestedBy())
                && v.input().idempotencyKey().equals(job.input().idempotencyKey())).findFirst();
        if(old.isPresent()) { sameRequest(old.get(),job); return old.get(); }
        if(jobs.containsKey(job.id())) throw new IllegalStateException("Job identity conflict");
        var queued=KnowledgeIndexJob.queued(job.id(),job.input(),now()); save(queued); return queued;
    }
    @Override public synchronized List<KnowledgeIndexJob> list(String base,int offset,int limit) {
        return jobs.values().stream().filter(j->j.input().baseId().equals(base))
                .sorted(Comparator.comparing(KnowledgeIndexJob::createdAt).reversed().thenComparing(KnowledgeIndexJob::id))
                .skip(offset).limit(limit).toList();
    }
    @Override public synchronized Optional<KnowledgeIndexJob> claimNext(String worker,int seconds) {
        leaseSeconds(seconds);
        return jobs.values().stream().filter(j->j.due(now()))
                .sorted(Comparator.comparing((KnowledgeIndexJob j)->j.progress().nextAttemptAt()).thenComparing(KnowledgeIndexJob::id))
                .findFirst().map(j->claim(j,worker,seconds)).filter(j->j.progress().state()==State.RUNNING);
    }
    @Override public synchronized List<Batch> batches(String id,Stage stage,int offset,int limit) {
        return manifests.values().stream().filter(b->b.jobId().equals(id) && b.stage()==stage && b.ordinal()>=offset)
                .sorted(Comparator.comparingInt(Batch::ordinal)).limit(limit).toList();
    }
    @Override protected void putBatch(Batch b) { manifests.put(b.jobId()+":"+b.stage()+":"+b.ordinal(),b); }
    @Override protected void putManifest(Manifest m) { stages.put(m.jobId()+":"+m.stage(),m); }
    @Override public synchronized Optional<Manifest> manifest(String id,Stage stage) { return Optional.ofNullable(stages.get(id+":"+stage)); }
}
