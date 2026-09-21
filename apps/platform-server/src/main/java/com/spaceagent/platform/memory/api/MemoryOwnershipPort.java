package com.spaceagent.platform.memory.api;

/**
 * Public memory ownership port exposed to other modules.
 */
public interface MemoryOwnershipPort {
    boolean isOwner(String memoryId, String principalId);

    default boolean canAccessScope(com.spaceagent.platform.memory.domain.MemoryScopeRef scope, String principalId) {
        return principalId != null && principalId.equals(scope.scopeId());
    }
}
