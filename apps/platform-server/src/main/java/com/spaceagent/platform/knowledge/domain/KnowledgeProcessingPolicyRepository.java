package com.spaceagent.platform.knowledge.domain;
public interface KnowledgeProcessingPolicyRepository {
    Snapshot get(String base);
    Snapshot save(String base,long expectedRevision,KnowledgeProcessingPolicy policy);
    record Snapshot(long revision,KnowledgeProcessingPolicy policy){}
}
