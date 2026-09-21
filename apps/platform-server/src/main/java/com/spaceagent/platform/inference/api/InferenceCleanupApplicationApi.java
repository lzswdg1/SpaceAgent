package com.spaceagent.platform.inference.api;

public interface InferenceCleanupApplicationApi {
    void cleanupOrganization(String organizationId);
    void cleanupUser(String userId);
}
