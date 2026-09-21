package com.spaceagent.platform.knowledge.api;
public interface KnowledgeIndexDeletionApplicationApi {
    View delete(KnowledgeBaseApplicationApi.Actor actor,String base,String document,long revision);
    View status(KnowledgeBaseApplicationApi.Actor actor,String base,String document);
    record View(String documentId,String state,long pendingGenerations) {}
}
