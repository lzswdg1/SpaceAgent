package com.spaceagent.platform.knowledge.api;
public interface KnowledgeIndexRepairApi {
    View request(KnowledgeBaseApplicationApi.Actor actor,String base,String document,String generation,String key);
    View get(KnowledgeBaseApplicationApi.Actor actor,String id);
    record View(String id,String baseId,String documentId,String generationId,String state,String safeCode){}
}
