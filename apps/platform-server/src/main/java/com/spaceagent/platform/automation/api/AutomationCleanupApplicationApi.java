package com.spaceagent.platform.automation.api;

public interface AutomationCleanupApplicationApi {
    void cleanupOrganization(String organizationId);

    UserCleanupView cleanupUser(String userId);

    record UserCleanupView(boolean blocked, String safeCode) {
    }
}
