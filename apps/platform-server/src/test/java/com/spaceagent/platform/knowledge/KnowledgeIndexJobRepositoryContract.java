package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.domain.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;
import static org.assertj.core.api.Assertions.*;

abstract class KnowledgeIndexJobRepositoryContract {
    protected KnowledgeIndexJobRepository repo;
    protected abstract void expire(String id);
    protected abstract void due(String id);
    protected abstract KnowledgeIndexJobRepository restart();
    protected void seed(Input input) {}
    protected KnowledgeIndexJob candidate(int budget) {
        String id=UUID.randomUUID().toString();
        var input=new Input("base",id,"owner","org","key-"+id,"a".repeat(64),"dense_v1",budget);
        seed(input); return KnowledgeIndexJob.queued(id,input,Instant.now());
    }
    protected void checkpoint(KnowledgeIndexJob job,int count) {
        repo.planStage(job.lease(),new Manifest(job.id(),job.progress().stage(),"b".repeat(64),count));
        for(int n=0;n<count;n++) {
            var b=repo.beginBatch(job.lease(),job.progress().stage(),n,"c".repeat(64),1);
            repo.completeBatch(job.lease(),new Batch(b.jobId(),b.stage(),b.ordinal(),b.inputHash(),b.itemCount(),
                    BatchState.COMPLETED,"knowledge-index/"+job.id()+"/result-"+n,"d".repeat(64)));
        }
    }
    @Test void enqueueReplaysOnlyTheSameImmutableRequest() {
        var candidate=candidate(5); var job=repo.enqueue(candidate);
        assertThat(repo.enqueue(candidate)).isEqualTo(job);
        var i=candidate.input();
        var changed=new Input(i.baseId(),i.generationId(),i.requestedBy(),i.organizationId(),i.idempotencyKey(),"b".repeat(64),i.storageSchema(),5);
        assertThatThrownBy(()->repo.enqueue(KnowledgeIndexJob.queued(UUID.randomUUID().toString(),changed,Instant.now())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repo.list("other-base",0,10)).isEmpty();
    }
    @Test void checkpointSurvivesRepositoryRestartAndOldFenceCannotCommit() {
        var original=repo.enqueue(candidate(5)); var claim=repo.claimNext("worker-a",60).orElseThrow();
        assertThat(repo.claimNext("worker-b",60)).isEmpty();
        checkpoint(claim,2);
        expire(claim.id()); repo=restart();
        var recovered=repo.claimNext("worker-b",60).orElseThrow();
        assertThat(recovered.id()).isEqualTo(original.id());
        assertThat(recovered.progress().fence()).isGreaterThan(claim.progress().fence());
        assertThat(repo.batches(claim.id(),Stage.PARSING,0,100)).hasSize(2).allMatch(b->b.state()==BatchState.COMPLETED);
        assertThat(repo.renew(claim.lease(),60)).isFalse();
        assertThatThrownBy(()->repo.advance(claim.lease(),Stage.PARSING)).isInstanceOf(IllegalStateException.class);
        assertThat(repo.beginBatch(recovered.lease(),Stage.PARSING,0,"c".repeat(64),1).state()).isEqualTo(BatchState.COMPLETED);
        var next=repo.advance(recovered.lease(),Stage.PARSING);
        assertThat(next.progress().stage()).isEqualTo(Stage.CHUNKING);
        assertThatThrownBy(()->repo.advance(recovered.lease(),Stage.PARSING)).isInstanceOf(IllegalStateException.class);
    }
    @Test void expiredInflightBatchBecomesUnknownAndCannotBeRetried() {
        repo.enqueue(candidate(5)); var claim=repo.claimNext("worker",60).orElseThrow();
        repo.planStage(claim.lease(),new Manifest(claim.id(),Stage.PARSING,"b".repeat(64),1));
        repo.beginBatch(claim.lease(),Stage.PARSING,0,"c".repeat(64),1);
        assertThatThrownBy(()->repo.beginBatch(claim.lease(),Stage.PARSING,0,"c".repeat(64),1)).isInstanceOf(IllegalStateException.class);
        expire(claim.id()); repo=restart();
        assertThat(repo.claimNext("replacement",60)).isEmpty();
        var stopped=repo.find(claim.id()).orElseThrow();
        assertThat(stopped.progress().state()).isEqualTo(State.RECONCILIATION_REQUIRED);
        assertThat(repo.batches(claim.id(),Stage.PARSING,0,100).getFirst().state()).isEqualTo(BatchState.UNKNOWN);
        assertThatThrownBy(()->repo.retry(stopped.id(),stopped.progress().revision())).isInstanceOf(IllegalStateException.class);
    }
    @Test void cancellationInvalidatesLeaseAndCannotBeOverwrittenOrRetried() {
        repo.enqueue(candidate(5)); var claim=repo.claimNext("worker",60).orElseThrow();
        checkpoint(claim,1);
        assertThatThrownBy(()->repo.cancel(claim.id(),1)).isInstanceOf(IllegalStateException.class);
        var cancelled=repo.cancel(claim.id(),claim.progress().revision());
        assertThat(cancelled.progress().state()).isEqualTo(State.CANCELLED);
        assertThat(repo.renew(claim.lease(),60)).isFalse();
        assertThatThrownBy(()->repo.advance(claim.lease(),Stage.PARSING)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->repo.retry(cancelled.id(),cancelled.progress().revision())).isInstanceOf(IllegalStateException.class);
        assertThat(repo.batches(claim.id(),Stage.PARSING,0,100)).hasSize(1);
    }
    @Test void retryBackoffAndLifetimeAttemptBudgetAreBounded() {
        repo.enqueue(candidate(2)); var a=repo.claimNext("worker",60).orElseThrow();
        var waiting=repo.fail(a.lease(),Failure.RETRYABLE,"INDEX_TEMPORARILY_UNAVAILABLE");
        assertThat(waiting.progress().state()).isEqualTo(State.RETRY_WAIT);
        assertThat(repo.claimNext("worker",60)).isEmpty();
        due(a.id()); var b=repo.claimNext("worker",60).orElseThrow();
        var failed=repo.fail(b.lease(),Failure.RETRYABLE,"INDEX_TEMPORARILY_UNAVAILABLE");
        assertThat(failed.progress().state()).isEqualTo(State.FAILED);
        assertThat(failed.progress().attempts()).isEqualTo(2);
        assertThatThrownBy(()->repo.retry(failed.id(),failed.progress().revision())).isInstanceOf(IllegalStateException.class);
        assertThat(repo.claimNext("worker",60)).isEmpty();
    }
    @Test void manifestsRequireEveryBatchAndCannotChangeInputsOrOutputs() {
        repo.enqueue(candidate(5)); var claim=repo.claimNext("worker",60).orElseThrow();
        assertThatThrownBy(()->repo.beginBatch(claim.lease(),Stage.PARSING,0,"c".repeat(64),1)).isInstanceOf(IllegalStateException.class);
        repo.planStage(claim.lease(),new Manifest(claim.id(),Stage.PARSING,"b".repeat(64),2));
        assertThatThrownBy(()->repo.planStage(claim.lease(),new Manifest(claim.id(),Stage.PARSING,"b".repeat(64),1)))
                .isInstanceOf(IllegalStateException.class);
        repo.beginBatch(claim.lease(),Stage.PARSING,0,"c".repeat(64),1);
        var output=new Batch(claim.id(),Stage.PARSING,0,"c".repeat(64),1,BatchState.COMPLETED,
                "knowledge-index/"+claim.id()+"/result","d".repeat(64));
        repo.completeBatch(claim.lease(),output); repo.completeBatch(claim.lease(),output);
        assertThatThrownBy(()->repo.completeBatch(claim.lease(),new Batch(claim.id(),Stage.PARSING,0,"c".repeat(64),1,
                BatchState.COMPLETED,output.outputReference(),"e".repeat(64)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->repo.advance(claim.lease(),Stage.PARSING)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->repo.beginBatch(claim.lease(),Stage.PARSING,2,"c".repeat(64),1)).isInstanceOf(IllegalStateException.class);
    }
    @Test void safeManualRetryPreservesAttemptBudgetAndUnknownFailureNeverRetries() {
        repo.enqueue(candidate(5)); var claim=repo.claimNext("worker",60).orElseThrow();
        var failed=repo.fail(claim.lease(),Failure.PERMANENT,"INDEX_INPUT_UNAVAILABLE");
        var queued=repo.retry(failed.id(),failed.progress().revision());
        assertThat(queued.progress().attempts()).isEqualTo(1);
        assertThatThrownBy(()->repo.retry(failed.id(),failed.progress().revision())).isInstanceOf(IllegalStateException.class);
        var replacement=repo.claimNext("worker",60).orElseThrow();
        var unknown=repo.fail(replacement.lease(),Failure.UNKNOWN,"INDEX_REMOTE_TIMEOUT");
        assertThat(unknown.progress().state()).isEqualTo(State.RECONCILIATION_REQUIRED);
        assertThatThrownBy(()->repo.retry(unknown.id(),unknown.progress().revision())).isInstanceOf(IllegalStateException.class);
        assertThat(repo.claimNext("worker",60)).isEmpty();
    }
    @Test void inflightFailureIsNeverBlindlyRetriedAndUnsafeReferencesAreRejected() {
        repo.enqueue(candidate(5)); var claim=repo.claimNext("worker",60).orElseThrow();
        repo.planStage(claim.lease(),new Manifest(claim.id(),Stage.PARSING,"b".repeat(64),1));
        repo.beginBatch(claim.lease(),Stage.PARSING,0,"c".repeat(64),1);
        assertThatThrownBy(()->new Batch(claim.id(),Stage.PARSING,0,"c".repeat(64),1,BatchState.COMPLETED,
                "https://external.invalid/private?token=secret","d".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        var failed=repo.fail(claim.lease(),Failure.RETRYABLE,"INDEX_REMOTE_TIMEOUT");
        assertThat(failed.progress().state()).isEqualTo(State.RECONCILIATION_REQUIRED);
        assertThat(repo.batches(claim.id(),Stage.PARSING,0,100).getFirst().state()).isEqualTo(BatchState.UNKNOWN);
    }
    @Test void provenRejectionAllowsBoundedRetryWithoutLosingTheImmutableManifest() {
        repo.enqueue(candidate(2)); var first=repo.claimNext("worker",60).orElseThrow();
        repo.planStage(first.lease(),new Manifest(first.id(),Stage.PARSING,"b".repeat(64),1));
        repo.beginBatch(first.lease(),Stage.PARSING,0,"c".repeat(64),1);
        repo.rejectBatch(first.lease(),Stage.PARSING,0);
        var failed=repo.fail(first.lease(),Failure.PERMANENT,"INDEX_PROVIDER_REJECTED");
        assertThat(failed.progress().state()).isEqualTo(State.FAILED);
        repo.retry(first.id(),failed.progress().revision()); var next=repo.claimNext("worker",60).orElseThrow();
        assertThat(repo.beginBatch(next.lease(),Stage.PARSING,0,"c".repeat(64),1).state()).isEqualTo(BatchState.IN_FLIGHT);
        assertThatThrownBy(()->repo.planStage(next.lease(),new Manifest(next.id(),Stage.PARSING,"d".repeat(64),1))).isInstanceOf(IllegalStateException.class);
    }
    @Test void completedPipelineWaitsForSeparateVerifiedActivation() {
        repo.enqueue(candidate(5)); var claim=repo.claimNext("worker",60).orElseThrow();
        for(Stage s:Stage.values()) {
            if(s==Stage.READY_TO_ACTIVATE) break;
            checkpoint(claim,1); claim=repo.advance(claim.lease(),s);
        }
        assertThat(claim.progress().stage()).isEqualTo(Stage.READY_TO_ACTIVATE);
        assertThat(claim.progress().state()).isEqualTo(State.RUNNING);
        var last=claim;
        assertThatThrownBy(()->repo.advance(last.lease(),Stage.READY_TO_ACTIVATE)).isInstanceOf(IllegalStateException.class);
    }
}
