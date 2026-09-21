package com.spaceagent.platform.project.api;

public record UpdateProjectCommand(
        String tenantId,
        String userId,
        String projectId,
        String name,
        String description,
        boolean descriptionPresent) {
}
