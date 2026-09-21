package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob;
import com.spaceagent.platform.knowledge.domain.KnowledgeIndexJobRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;

/** Shared transition protocol; adapters supply an atomic transaction and authoritative clock. */
public abstract class AbstractKnowledgeIndexJobRepository implements KnowledgeIndexJobRepository {
    protected abstract <T> T transaction(Supplier<T> operation);
    protected abstract KnowledgeIndexJob lock(String id);
    protected abstract Instant now();
    protected abstract void save(KnowledgeIndexJob job);
    protected abstract void putBatch(Batch batch);
    protected abstract void putManifest(Manifest manifest);

    protected KnowledgeIndexJob claim(KnowledgeIndexJob job,String worker,int seconds) {
        boolean uncertain=uncertain(job);
        var claimed=job.claim(worker,seconds,now(),uncertain);
        if(uncertain) markUnknown(job);
        save(claimed);
        return claimed;
    }
    private boolean uncertain(KnowledgeIndexJob job) {
        return batches(job.id(),job.progress().stage(),0,512).stream().anyMatch(b->b.state()==BatchState.IN_FLIGHT || b.state()==BatchState.UNKNOWN);
    }
    private void markUnknown(KnowledgeIndexJob job) {
        batches(job.id(),job.progress().stage(),0,512).stream().filter(b->b.state()==BatchState.IN_FLIGHT)
                .map(Batch::unknown).forEach(this::putBatch);
    }
    private KnowledgeIndexJob owned(Lease lease) {
        var job=lock(lease.jobId());
        if(!job.owns(lease,now())) throw new IllegalStateException("Index job lease is no longer valid");
        return job;
    }
    @Override public boolean renew(Lease lease,int seconds) {
        leaseSeconds(seconds);
        return transaction(()->{
            var job=lock(lease.jobId());
            if(!job.owns(lease,now())) return false;
            save(job.renew(seconds,now())); return true;
        });
    }
    @Override public Batch beginBatch(Lease lease,Stage stage,int ordinal,String hash,int items) {
        var candidate=new Batch(lease.jobId(),stage,ordinal,hash,items,BatchState.IN_FLIGHT,null,null);
        return transaction(()->{
            var job=owned(lease);
            if(job.progress().stage()!=stage) throw new IllegalStateException("Index stage changed");
            var plan=manifest(job.id(),stage).orElseThrow(()->new IllegalStateException("Missing stage manifest"));
            if(ordinal>=plan.batchCount()) throw new IllegalStateException("Batch exceeds stage manifest");
            var existing=batches(job.id(),stage,ordinal,1).stream().filter(b->b.ordinal()==ordinal).findFirst();
            if(existing.isPresent()) {
                var batch=existing.get();
                if(!batch.inputHash().equals(hash) || batch.itemCount()!=items) throw new IllegalStateException("Immutable batch input conflict");
                if(batch.state()==BatchState.REJECTED) { putBatch(candidate); return candidate; }
                if(batch.state()!=BatchState.COMPLETED) throw new IllegalStateException("Batch effect is already in flight or unknown");
                return batch; // Reuse checkpoint; caller must not repeat its effect.
            }
            putBatch(candidate); return candidate;
        });
    }
    @Override public void rejectBatch(Lease lease,Stage stage,int ordinal) {
        transaction(()->{
            var job=owned(lease);
            if(job.progress().stage()!=stage) throw new IllegalStateException("Index stage changed");
            var batch=batches(job.id(),stage,ordinal,1).stream().filter(b->b.ordinal()==ordinal).findFirst().orElseThrow();
            if(batch.state()!=BatchState.IN_FLIGHT) throw new IllegalStateException("Only a proven rejected in-flight batch can be closed");
            putBatch(new Batch(job.id(),stage,ordinal,batch.inputHash(),batch.itemCount(),BatchState.REJECTED,null,null)); return null;
        });
    }
    @Override public Manifest planStage(Lease lease,Manifest plan) {
        if(!lease.jobId().equals(plan.jobId())) throw new IllegalArgumentException("Manifest job mismatch");
        return transaction(()->{
            var job=owned(lease);
            if(job.progress().stage()!=plan.stage()) throw new IllegalStateException("Index stage changed");
            var prior=manifest(job.id(),plan.stage());
            if(prior.isPresent() && !prior.get().equals(plan)) throw new IllegalStateException("Immutable manifest conflict");
            putManifest(plan); return plan;
        });
    }
    @Override public Batch completeBatch(Lease lease,Batch output) {
        if(output.state()!=BatchState.COMPLETED || !lease.jobId().equals(output.jobId()))
            throw new IllegalArgumentException("Confirmed output must belong to the claimed job");
        return transaction(()->{
            var job=owned(lease);
            if(job.progress().stage()!=output.stage()) throw new IllegalStateException("Index stage changed");
            var previous=batches(job.id(),output.stage(),output.ordinal(),1).stream()
                    .filter(b->b.ordinal()==output.ordinal()).findFirst().orElseThrow(()->new IllegalStateException("Missing batch intent"));
            if(!previous.inputHash().equals(output.inputHash()) || previous.itemCount()!=output.itemCount()
                    || previous.state()==BatchState.UNKNOWN || previous.state()==BatchState.REJECTED
                    || previous.state()==BatchState.COMPLETED && !previous.equals(output))
                throw new IllegalStateException("Immutable batch output conflict");
            putBatch(output); return output;
        });
    }
    @Override public KnowledgeIndexJob advance(Lease lease,Stage expectedStage) {
        return transaction(()->{
            var job=owned(lease);
            if(job.progress().stage()!=expectedStage) throw new IllegalStateException("Index stage changed");
            var plan=manifest(job.id(),expectedStage).orElseThrow(()->new IllegalStateException("Missing stage manifest"));
            var completed=batches(job.id(),expectedStage,0,512);
            if(completed.size()!=plan.batchCount()) throw new IllegalStateException("Incomplete stage manifest");
            var changed=job.advance(completed,now());
            save(changed); return changed;
        });
    }
    @Override public KnowledgeIndexJob fail(Lease lease,Failure failure,String safeCode) {
        Objects.requireNonNull(failure);
        return transaction(()->{
            var job=owned(lease);
            var changed=job.fail(failure,uncertain(job),safeCode,now());
            if(changed.progress().state()==State.RECONCILIATION_REQUIRED) markUnknown(job);
            save(changed); return changed;
        });
    }
    @Override public KnowledgeIndexJob cancel(String id,long revision) {
        return transaction(()->{
            var job=lock(id); expected(job,revision);
            var changed=job.cancel(now()); markUnknown(job); save(changed); return changed;
        });
    }
    @Override public KnowledgeIndexJob retry(String id,long revision) {
        return transaction(()->{
            var job=lock(id); expected(job,revision);
            if(uncertain(job)) throw new IllegalStateException("Unknown effects require reconciliation");
            var changed=job.retry(now()); save(changed); return changed;
        });
    }
    protected static void expected(KnowledgeIndexJob job,long revision) {
        if(job.progress().revision()!=revision) throw new IllegalStateException("Index job revision changed");
    }
    protected static void sameRequest(KnowledgeIndexJob actual,KnowledgeIndexJob requested) {
        if(!actual.input().equals(requested.input())) throw new IllegalStateException("Index job idempotency conflict");
    }
    protected static boolean pending(KnowledgeIndexJob job) {
        return job.progress().state()==State.QUEUED || job.progress().state()==State.RUNNING || job.progress().state()==State.RETRY_WAIT;
    }
}
