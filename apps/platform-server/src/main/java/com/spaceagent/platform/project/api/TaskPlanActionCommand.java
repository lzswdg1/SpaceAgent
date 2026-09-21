package com.spaceagent.platform.project.api;

public record TaskPlanActionCommand(
        String tenantId,
        String userId,
        String projectId,
        String rootTaskId,
        String taskPlanId,
        TaskPlanAction action) {
}
