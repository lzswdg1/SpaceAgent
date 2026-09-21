package com.spaceagent.platform.knowledge.api;

/**
 * Public knowledge ownership port exposed to other modules.
 */
public interface KnowledgeOwnershipPort {
    boolean canAccess(String documentId, String principalId);
}
