package com.spaceagent.platform.project.api;

public record GetTaskPlanQuery(
        String tenantId,
        String userId,
        String projectId,
        String rootTaskId,
        String taskPlanId) {
}
