package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.ProjectRole;

import java.time.Instant;

public record ProjectMembershipView(
        String id,
        String projectId,
        String userId,
        ProjectRole role,
        Instant createdAt) {
}
