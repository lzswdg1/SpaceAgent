package com.spaceagent.platform.knowledge.domain;
import java.util.*;

public interface KnowledgeOperationalRepository {
    void admit(String baseId,String tenantId,boolean newDocument,long bytes,int maxDocuments,int maxPending,long maxBytes);
    Optional<String> acquireQuery(String tenantId,int limit);
    void releaseQuery(String leaseId);
    Map<String,Long> statistics();
    default void expireQueries(){}
}
