package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.ProjectStatus;

import java.time.Instant;

public record ProjectView(
        String id,
        String tenantId,
        String ownerId,
        String name,
        String description,
        ProjectStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
