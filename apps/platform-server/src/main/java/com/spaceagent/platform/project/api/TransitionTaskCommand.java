package com.spaceagent.platform.project.api;

public record TransitionTaskCommand(
        String tenantId,
        String userId,
        String projectId,
        String taskId,
        TaskTransition transition) {
}
