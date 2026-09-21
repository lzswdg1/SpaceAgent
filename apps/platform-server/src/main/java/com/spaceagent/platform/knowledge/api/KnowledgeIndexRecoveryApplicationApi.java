package com.spaceagent.platform.knowledge.api;
public interface KnowledgeIndexRecoveryApplicationApi {
    KnowledgeIndexJobApplicationApi.JobView reconcile(KnowledgeBaseApplicationApi.Actor actor,String jobId,long expectedRevision);
}
