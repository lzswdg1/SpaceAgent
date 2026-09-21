package com.spaceagent.platform.agent.api;

public interface AgentCleanupApplicationApi {
    void cleanupOrganization(String organizationId);
    void cleanupUser(String userId);
}
