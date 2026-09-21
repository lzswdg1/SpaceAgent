package com.spaceagent.platform.artifact.api;

import java.util.List;

public interface ArtifactCleanupApplicationApi {
    void cleanupOrganization(String organizationId);
    void cleanupRuns(List<String> agentRunIds);
}
