package com.spaceagent.platform.knowledge.api;
import java.util.*;
public interface KnowledgeLegacyMigrationApi {
    List<Item> inspect(KnowledgeBaseApplicationApi.Actor actor,String baseId,int offset,int limit);
    KnowledgeIndexIntakeApplicationApi.IntakeView copy(KnowledgeBaseApplicationApi.Actor actor,String legacyBaseId,String legacyDocumentId,String targetBaseId,String spaceId,String key);
    record Item(String documentId,String name,String state,String reason,String copiedBaseId,String copiedDocumentId,String indexJobId){}
}
