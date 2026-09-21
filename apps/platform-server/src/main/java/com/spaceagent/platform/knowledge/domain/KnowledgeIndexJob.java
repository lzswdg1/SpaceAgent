package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable control state only. No document text, vectors, credentials or remote exception messages. */
public record KnowledgeIndexJob(String id, Input input, Progress progress, Instant createdAt, Instant updatedAt) {
    public enum Stage { PARSING, CHUNKING, EMBEDDING, WRITING, VERIFYING, READY_TO_ACTIVATE }
    public enum State { QUEUED, RUNNING, RETRY_WAIT, FAILED, CANCELLED, RECONCILIATION_REQUIRED, COMPLETED }
    public enum BatchState { IN_FLIGHT, COMPLETED, REJECTED, UNKNOWN }
    public enum Failure { RETRYABLE, PERMANENT, UNKNOWN }

    public record Input(String baseId, String generationId, String requestedBy, String organizationId,
                        String idempotencyKey, String requestHash, String storageSchema, int maxAttempts) {
        public Input {
            identifier(baseId,36); identifier(generationId,36); identifier(requestedBy,36);
            if (organizationId != null) identifier(organizationId,36);
            identifier(idempotencyKey,128); hash(requestHash);
            if (!java.util.Set.of("dense_v1","hybrid_v2").contains(storageSchema)) throw new IllegalArgumentException("Unsupported index schema");
            if (maxAttempts < 1 || maxAttempts > 8) throw new IllegalArgumentException("Invalid attempt budget");
        }
    }
    public record Progress(Stage stage, State state, long revision, long fence, String worker,
                           Instant leaseUntil, int attempts, Instant nextAttemptAt, String errorCode) {
        public Progress {
            Objects.requireNonNull(stage); Objects.requireNonNull(state); Objects.requireNonNull(nextAttemptAt);
            if (revision < 1 || fence < 0 || attempts < 0) throw new IllegalArgumentException("Invalid job counters");
            if (state == State.RUNNING) { identifier(worker,100); Objects.requireNonNull(leaseUntil); }
            else if (worker != null || leaseUntil != null) throw new IllegalArgumentException("Inactive lease");
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]{0,79}"))
                throw new IllegalArgumentException("Only safe error codes may be persisted");
        }
    }
    public record Lease(String jobId, String worker, long fence) {
        public Lease { identifier(jobId,36); identifier(worker,100); if (fence < 1) throw new IllegalArgumentException("Invalid fence"); }
    }
    public record Manifest(String jobId,Stage stage,String inputHash,int batchCount) {
        public Manifest {
            identifier(jobId,36); hash(inputHash); Objects.requireNonNull(stage);
            if(stage==Stage.READY_TO_ACTIVATE || batchCount<1 || batchCount>512)
                throw new IllegalArgumentException("Invalid stage manifest");
        }
    }
    public record Batch(String jobId, Stage stage, int ordinal, String inputHash, int itemCount,
                        BatchState state, String outputReference, String outputHash) {
        public Batch {
            identifier(jobId,36); Objects.requireNonNull(stage); Objects.requireNonNull(state); hash(inputHash);
            if (ordinal < 0 || ordinal >= 512 || itemCount < 1 || itemCount > 64 || stage == Stage.READY_TO_ACTIVATE)
                throw new IllegalArgumentException("Invalid batch bounds");
            if (state == BatchState.COMPLETED) {
                // Opaque owner-managed keys, never arbitrary paths, URLs or credentials.
                if (outputReference == null || !outputReference.matches("knowledge-index/" + java.util.regex.Pattern.quote(jobId)
                        + "/[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid managed output reference");
                hash(outputHash);
            } else if (outputReference != null || outputHash != null) throw new IllegalArgumentException("Unconfirmed batch output");
        }
        public Batch unknown() { return new Batch(jobId,stage,ordinal,inputHash,itemCount,BatchState.UNKNOWN,null,null); }
    }

    public KnowledgeIndexJob {
        identifier(id,36); Objects.requireNonNull(input); Objects.requireNonNull(progress);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
    }
    public static KnowledgeIndexJob queued(String id, Input input, Instant now) {
        return new KnowledgeIndexJob(id,input,new Progress(Stage.PARSING,State.QUEUED,1,0,null,null,0,now,null),now,now);
    }
    public Lease lease() { return new Lease(id,progress.worker(),progress.fence()); }
    public boolean owns(Lease lease, Instant now) {
        return id.equals(lease.jobId()) && progress.state()==State.RUNNING && progress.fence()==lease.fence()
                && Objects.equals(progress.worker(),lease.worker()) && progress.leaseUntil().isAfter(now);
    }
    public boolean due(Instant now) {
        return switch(progress.state()) {
            case QUEUED, RETRY_WAIT -> !progress.nextAttemptAt().isAfter(now);
            case RUNNING -> !progress.leaseUntil().isAfter(now);
            default -> false;
        };
    }
    public KnowledgeIndexJob claim(String worker, int seconds, Instant now, boolean uncertainBatch) {
        leaseSeconds(seconds); identifier(worker,100);
        if (!due(now)) throw new IllegalStateException("Job is not claimable");
        if (uncertainBatch) return stop(State.RECONCILIATION_REQUIRED,"INDEX_BATCH_UNKNOWN",now,now);
        if (progress.attempts() >= input.maxAttempts()) return stop(State.FAILED,"INDEX_ATTEMPTS_EXHAUSTED",now,now);
        return change(new Progress(progress.stage(),State.RUNNING,progress.revision()+1,progress.fence()+1,
                worker,now.plusSeconds(seconds),progress.attempts()+1,now,null),now);
    }
    public KnowledgeIndexJob renew(int seconds, Instant now) {
        leaseSeconds(seconds);
        return change(new Progress(progress.stage(),progress.state(),progress.revision()+1,progress.fence(),
                progress.worker(),now.plusSeconds(seconds),progress.attempts(),progress.nextAttemptAt(),progress.errorCode()),now);
    }
    public KnowledgeIndexJob advance(List<Batch> batches, Instant now) {
        if (progress.stage()==Stage.READY_TO_ACTIVATE || batches.isEmpty()
                || batches.stream().anyMatch(b->b.stage()!=progress.stage() || b.state()!=BatchState.COMPLETED))
            throw new IllegalStateException("Stage is not completely checkpointed");
        for(int i=0;i<batches.size();i++) if(batches.get(i).ordinal()!=i) throw new IllegalStateException("Batch manifest contains gaps");
        return change(new Progress(Stage.values()[progress.stage().ordinal()+1],State.RUNNING,progress.revision()+1,
                progress.fence(),progress.worker(),progress.leaseUntil(),progress.attempts(),progress.nextAttemptAt(),null),now);
    }
    public KnowledgeIndexJob fail(Failure failure, boolean uncertain, String code, Instant now) {
        if (failure==Failure.UNKNOWN || uncertain) return stop(State.RECONCILIATION_REQUIRED,"INDEX_BATCH_UNKNOWN",now,now);
        boolean retry=failure==Failure.RETRYABLE && progress.attempts()<input.maxAttempts();
        return stop(retry?State.RETRY_WAIT:State.FAILED,code,
                retry?now.plusSeconds(Math.min(300,5L << Math.min(progress.attempts(),6))):now,now);
    }
    public KnowledgeIndexJob cancel(Instant now) {
        if (progress.state()==State.COMPLETED) throw new IllegalStateException("Completed index cannot be cancelled");
        return stop(State.CANCELLED,"INDEX_CANCELLED",now,now);
    }
    public KnowledgeIndexJob retry(Instant now) {
        if (progress.state()!=State.FAILED || progress.attempts()>=input.maxAttempts())
            throw new IllegalStateException("Job is not safely retryable");
        return stop(State.QUEUED,null,now,now);
    }
    private KnowledgeIndexJob stop(State state,String code,Instant next,Instant now) {
        return change(new Progress(progress.stage(),state,progress.revision()+1,progress.fence()+1,null,null,
                progress.attempts(),next,code),now);
    }
    private KnowledgeIndexJob change(Progress value,Instant now) { return new KnowledgeIndexJob(id,input,value,createdAt,now); }
    public static void leaseSeconds(int value) { if(value<1 || value>300) throw new IllegalArgumentException("Invalid lease duration"); }
    private static void identifier(String value,int max) {
        if(value==null || value.length()>max || !value.matches("[A-Za-z0-9_.:-]+")) throw new IllegalArgumentException("Invalid index identity");
    }
    private static void hash(String value) {
        if(value==null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid index hash");
    }
}
