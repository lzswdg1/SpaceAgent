package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.Objects;

/** A user's explicit role within one Project. */
public final class ProjectMembership {

    private final String id;
    private final String projectId;
    private final String userId;
    private final ProjectRole role;
    private final Instant createdAt;

    private ProjectMembership(
            String id,
            String projectId,
            String userId,
            ProjectRole role,
            Instant createdAt) {
        this.id = requireText(id, "id");
        this.projectId = requireText(projectId, "projectId");
        this.userId = requireText(userId, "userId");
        this.role = Objects.requireNonNull(role, "role");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ProjectMembership create(
            String id,
            String projectId,
            String userId,
            ProjectRole role,
            Instant now) {
        return new ProjectMembership(id, projectId, userId, role, now);
    }

    public static ProjectMembership restore(
            String id,
            String projectId,
            String userId,
            ProjectRole role,
            Instant createdAt) {
        return new ProjectMembership(id, projectId, userId, role, createdAt);
    }

    public ProjectMembership changeRole(ProjectRole newRole) {
        return new ProjectMembership(
                id, projectId, userId,
                Objects.requireNonNull(newRole, "newRole"), createdAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() {
        return id;
    }

    public String projectId() {
        return projectId;
    }

    public String userId() {
        return userId;
    }

    public ProjectRole role() {
        return role;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
