package com.spaceagent.platform.project.api;

public record ListTasksQuery(String tenantId, String userId, String projectId) {
}
