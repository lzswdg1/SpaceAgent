package com.spaceagent.platform.knowledge.domain;

import java.util.Optional;

public interface KnowledgeIndexDeletionRepository {
    View request(String base,String document,long revision);
    boolean reserved(String base,String document);
    Optional<View> view(String base,String document);
    Optional<Claim> claim();
    boolean finish(Claim claim,boolean clean,String safeCode);
    boolean scopeCleanup(String userId,String organizationId);
    default int retireSuperseded(){return 0;}
    record View(String documentId,String state,long pendingGenerations) {}
    record Claim(String generationId,String baseId,String documentId,String jobId,VectorIndexGateway.Space space,
                 String scopeKey,String token,long fence,String requestTenant,String requestActor,String kind) {
        public Claim(String generationId,String baseId,String documentId,String jobId,VectorIndexGateway.Space space,String scopeKey,String token,long fence,String requestTenant,String requestActor){this(generationId,baseId,documentId,jobId,space,scopeKey,token,fence,requestTenant,requestActor,"DOCUMENT");}
    }
}
