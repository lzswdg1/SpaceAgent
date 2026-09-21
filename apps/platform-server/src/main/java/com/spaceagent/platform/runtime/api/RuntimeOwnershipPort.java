package com.spaceagent.platform.runtime.api;

/**
 * Public runtime ownership/visibility port exposed to other modules.
 */
public interface RuntimeOwnershipPort {
    boolean canObserve(String agentRunId, String tenantId, String principalId);
}
