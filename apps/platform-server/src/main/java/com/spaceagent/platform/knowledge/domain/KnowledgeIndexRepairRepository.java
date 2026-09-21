package com.spaceagent.platform.knowledge.domain;
import java.util.*;
public interface KnowledgeIndexRepairRepository {
    Repair enqueue(String base,String document,String generation,String tenant,String actor,String key);
    Optional<Repair> find(String id);
    Optional<Repair> claim();
    boolean renew(Repair repair);
    boolean finish(Repair repair,String error);
    Optional<String> completedIndexJob(String generation);
    record Repair(String id,String baseId,String documentId,String generationId,String tenantId,String actorId,String state,String token,long fence,String safeCode){}
}
