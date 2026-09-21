package com.spaceagent.platform.project.api;

public record GetSourceRepositoryQuery(
        String tenantId, String userId, String projectId, String sourceRepositoryId) { }
