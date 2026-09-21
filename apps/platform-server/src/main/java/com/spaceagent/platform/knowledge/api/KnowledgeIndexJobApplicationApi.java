package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.knowledge.domain.KnowledgeIndexJob;
import java.time.Instant;
import java.util.List;

public interface KnowledgeIndexJobApplicationApi {
    /** Internal ingestion contract; a staged generation must exist. No public source-registration bypass. */
    JobView enqueue(KnowledgeBaseApplicationApi.Actor actor,String baseId,String generationId,String idempotencyKey);
    JobView get(KnowledgeBaseApplicationApi.Actor actor,String jobId);
    List<JobView> list(KnowledgeBaseApplicationApi.Actor actor,String baseId,int offset,int limit);
    List<BatchView> batches(KnowledgeBaseApplicationApi.Actor actor,String jobId,KnowledgeIndexJob.Stage stage,int offset,int limit);
    JobView cancel(KnowledgeBaseApplicationApi.Actor actor,String jobId,long expectedRevision);
    JobView retry(KnowledgeBaseApplicationApi.Actor actor,String jobId,long expectedRevision);
    record JobView(String id,String baseId,String generationId,KnowledgeIndexJob.Stage stage,
                   KnowledgeIndexJob.State state,long revision,int attempts,int maxAttempts,
                   Instant nextAttemptAt,String safeErrorCode,Instant createdAt,Instant updatedAt) {}
    record BatchView(KnowledgeIndexJob.Stage stage,int ordinal,int itemCount,KnowledgeIndexJob.BatchState state) {}
}
