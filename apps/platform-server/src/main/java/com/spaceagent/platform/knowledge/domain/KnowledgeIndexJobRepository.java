package com.spaceagent.platform.knowledge.domain;

import java.util.List;
import java.util.Optional;
import static com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob.*;

/** All mutations atomically lock the job. The database clock, not a worker clock, fences writes. */
public interface KnowledgeIndexJobRepository {
    KnowledgeIndexJob enqueue(KnowledgeIndexJob job);
    Optional<KnowledgeIndexJob> find(String id);
    List<KnowledgeIndexJob> list(String baseId,int offset,int limit);
    Optional<KnowledgeIndexJob> claimNext(String worker,int leaseSeconds);
    boolean renew(Lease lease,int seconds);
    Manifest planStage(Lease lease,Manifest manifest);
    Optional<Manifest> manifest(String jobId,Stage stage);
    Batch beginBatch(Lease lease,Stage stage,int ordinal,String inputHash,int itemCount);
    Batch completeBatch(Lease lease,Batch output);
    void rejectBatch(Lease lease,Stage stage,int ordinal);
    List<Batch> batches(String jobId,Stage stage,int offset,int limit);
    KnowledgeIndexJob advance(Lease lease,Stage expectedStage);
    KnowledgeIndexJob fail(Lease lease,Failure failure,String safeCode);
    KnowledgeIndexJob cancel(String id,long expectedRevision);
    KnowledgeIndexJob retry(String id,long expectedRevision);
}
