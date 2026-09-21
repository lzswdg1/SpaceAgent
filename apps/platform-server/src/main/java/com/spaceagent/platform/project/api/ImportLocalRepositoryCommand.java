package com.spaceagent.platform.project.api;

public record ImportLocalRepositoryCommand(
        String tenantId, String userId, String projectId,
        String bridgeId, String rootHandle, String displayName, String defaultBranch) { }
