package com.spaceagent.platform.tooling.api;

public interface ToolingCleanupApplicationApi {
    void cleanupOrganization(String organizationId);
    void cleanupUser(String userId);
}
