package com.spaceagent.platform.knowledge.api;

public interface KnowledgeCleanupApplicationApi {
    CleanupResult cleanupUser(String userId);
    CleanupResult cleanupOrganization(String tenantId);
    record CleanupResult(boolean blocked,String safeCode) {
        public static CleanupResult completed(){return new CleanupResult(false,null);}
        public static CleanupResult blockedResult(){return new CleanupResult(true,"KNOWLEDGE_BYTES_DELETE_BLOCKED");}
    }
}
