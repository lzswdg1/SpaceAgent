package com.spaceagent.platform.project.api;

public record CreateProjectCommand(
        String tenantId,
        String userId,
        String name,
        String description) {
}
