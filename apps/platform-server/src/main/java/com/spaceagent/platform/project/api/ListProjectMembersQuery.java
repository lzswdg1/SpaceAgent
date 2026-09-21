package com.spaceagent.platform.project.api;

public record ListProjectMembersQuery(String tenantId, String userId, String projectId) {
}
