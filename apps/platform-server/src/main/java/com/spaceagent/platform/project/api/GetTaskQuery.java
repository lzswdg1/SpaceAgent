package com.spaceagent.platform.project.api;

public record GetTaskQuery(
        String tenantId,
        String userId,
        String projectId,
        String taskId) {
}
