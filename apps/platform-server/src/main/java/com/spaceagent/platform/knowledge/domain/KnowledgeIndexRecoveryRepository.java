package com.spaceagent.platform.knowledge.domain;
import java.util.Optional;
public interface KnowledgeIndexRecoveryRepository {
    Optional<KnowledgeIndexJob> claim(String jobId,long revision,String worker);
    void resolve(KnowledgeIndexJob.Lease lease,KnowledgeIndexJob.Batch resolved);
    void audit(String job,String actor,String outcome);
}
