package com.spaceagent.platform.project.api;

public record ArchiveSourceRepositoryCommand(
        String tenantId, String userId, String projectId, String sourceRepositoryId) { }
