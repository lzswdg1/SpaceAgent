package com.spaceagent.platform.project.domain;

import java.time.Instant;

public record ProjectDirectory(
        String id,
        String tenantId,
        String projectId,
        String sourceRepositoryId,
        String name,
        String relativePath,
        boolean defaultDirectory,
        ProjectDirectoryState state,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public ProjectDirectory {
        if (id == null || id.isBlank() || tenantId == null || tenantId.isBlank()
                || projectId == null || projectId.isBlank() || name == null || name.isBlank()
                || relativePath == null || relativePath.isBlank() || state == null
                || createdBy == null || createdBy.isBlank() || createdAt == null
                || updatedAt == null) {
            throw new IllegalArgumentException("ProjectDirectory identity is required");
        }
        if (!defaultDirectory && (sourceRepositoryId == null || sourceRepositoryId.isBlank())) {
            throw new IllegalArgumentException("Source ProjectDirectory requires a SourceRepository");
        }
        if (name.length() > 120 || relativePath.length() > 500) {
            throw new IllegalArgumentException("ProjectDirectory fields exceed their bounds");
        }
    }

    public static ProjectDirectory defaultFor(
            String id, Project project, String createdBy, Instant now) {
        return new ProjectDirectory(
                id, project.tenantId(), project.id(), null,
                boundedName(project.name()), ".", true,
                ProjectDirectoryState.ACTIVE, createdBy, now, now);
    }

    public static ProjectDirectory sourceRootFor(
            String id, Project project, SourceRepository source, String createdBy, Instant now) {
        if (!project.id().equals(source.projectId())
                || !project.tenantId().equals(source.tenantId())) {
            throw new IllegalArgumentException("ProjectDirectory source scope does not match Project");
        }
        return new ProjectDirectory(
                id, project.tenantId(), project.id(), source.id(),
                boundedName(source.displayName()), ".", false,
                ProjectDirectoryState.ACTIVE, createdBy, now, now);
    }

    public ProjectDirectory archive(Instant now) {
        if (state == ProjectDirectoryState.ARCHIVED) return this;
        return new ProjectDirectory(
                id, tenantId, projectId, sourceRepositoryId, name, relativePath,
                defaultDirectory, ProjectDirectoryState.ARCHIVED, createdBy, createdAt, now);
    }

    public ProjectDirectory bindManagedSource(String sourceId, Instant now) {
        if (sourceRepositoryId != null && !sourceRepositoryId.equals(sourceId)) throw new IllegalStateException("Root source already bound");
        return new ProjectDirectory(id,tenantId,projectId,sourceId,name,relativePath,defaultDirectory,state,createdBy,createdAt,now);
    }

    private static String boundedName(String value) {
        return value.substring(0, Math.min(120, value.length()));
    }
}
