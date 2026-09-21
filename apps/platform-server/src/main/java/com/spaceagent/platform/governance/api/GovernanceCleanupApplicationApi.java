package com.spaceagent.platform.governance.api;

public interface GovernanceCleanupApplicationApi {
    void cleanupOrganization(String organizationId);
    void cleanupUser(String userId);
}
