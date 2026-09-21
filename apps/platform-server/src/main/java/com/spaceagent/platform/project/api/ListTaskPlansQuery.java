package com.spaceagent.platform.project.api;

public record ListTaskPlansQuery(
        String tenantId,
        String userId,
        String projectId,
        String rootTaskId) {
}
