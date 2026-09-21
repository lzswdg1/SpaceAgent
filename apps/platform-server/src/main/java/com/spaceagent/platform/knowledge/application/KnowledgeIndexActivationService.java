package com.spaceagent.platform.knowledge.application;

import com.spaceagent.platform.identity.api.IdentityActivityApplicationApi;
import com.spaceagent.platform.knowledge.api.*;
import com.spaceagent.platform.knowledge.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short PostgreSQL transaction, deliberately separated from remote Milvus calls. */
@Service
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class KnowledgeIndexActivationService {
    private final KnowledgeIndexPublicationRepository publications;
    private final KnowledgeIndexJobRepository jobs;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeAccessApplicationApi access;
    private final IdentityActivityApplicationApi activity;
    public KnowledgeIndexActivationService(KnowledgeIndexPublicationRepository publications,KnowledgeIndexJobRepository jobs,
            KnowledgeBaseRepository bases,KnowledgeAccessApplicationApi access,IdentityActivityApplicationApi activity) {
        this.publications=publications;this.jobs=jobs;this.bases=bases;this.access=access;this.activity=activity;
    }
    @Transactional public KnowledgeIndexPublicationRepository.Head activate(KnowledgeIndexJob.Lease lease) {
        var job=jobs.find(lease.jobId()).orElseThrow();
        bases.lock(job.input().baseId()).orElseThrow();
        if(!activity.isUserActive(job.input().requestedBy())) throw new IllegalStateException("Index actor is no longer active");
        access.authorize(new KnowledgeBaseApplicationApi.Actor(job.input().requestedBy(),job.input().organizationId()),job.input().baseId(),KnowledgeBase.Permission.WRITE);
        return publications.activate(lease);
    }
}
