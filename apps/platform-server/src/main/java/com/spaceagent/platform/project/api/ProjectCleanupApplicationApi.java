package com.spaceagent.platform.project.api;

import java.util.List;

public interface ProjectCleanupApplicationApi {
    default boolean hasRetainedCodeStorage(String organizationId) { return false; }
    CleanupMemoryScopes resolveCleanupMemoryScopes(String organizationId);
    void cleanupOrganization(String organizationId);
    void cleanupUser(String userId);

    record CleanupMemoryScopes(List<String> projectIds, List<String> taskIds) {
    }
}
