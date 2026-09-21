package com.spaceagent.platform.project.domain;

import java.time.Instant;

/**
 * A Git/worktree workspace associated with a task.
 */
public record Workspace(
        String id,
        String tenantId,
        String projectId,
        String projectDirectoryId,
        String taskId,
        String sourceRepositoryId,
        String bridgeId,
        String isolationKey,
        WorkspaceMode mode,
        String worktreeKey,
        String baseRef,
        String branchName,
        String worktreeRef,
        String headCommit,
        boolean writable,
        WorkspaceState state,
        String failureReason,
        long revision,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public Workspace {
        if (id == null || id.isBlank() || tenantId == null || tenantId.isBlank()
                || projectId == null || projectId.isBlank() || taskId == null || taskId.isBlank()
                || sourceRepositoryId == null || sourceRepositoryId.isBlank()
                || isolationKey == null || isolationKey.isBlank()
                || worktreeKey == null || worktreeKey.isBlank()
                || baseRef == null || baseRef.isBlank() || branchName == null || branchName.isBlank()
                || mode == null || state == null || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("Workspace identity is required");
        }
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }

    /** Compatibility constructor before ProjectDirectory binding. */
    public Workspace(
            String id, String tenantId, String projectId, String taskId,
            String sourceRepositoryId, String bridgeId, String isolationKey,
            WorkspaceMode mode, String worktreeKey, String baseRef, String branchName,
            String worktreeRef, String headCommit, boolean writable, WorkspaceState state,
            String failureReason, long revision, String createdBy,
            Instant createdAt, Instant updatedAt) {
        this(id, tenantId, projectId, null, taskId, sourceRepositoryId, bridgeId,
                isolationKey, mode, worktreeKey, baseRef, branchName, worktreeRef,
                headCommit, writable, state, failureReason, revision, createdBy,
                createdAt, updatedAt);
    }

    public Workspace(
            String id, String projectId, String taskId, String worktreeRef,
            WorkspaceState state, Instant createdAt, Instant updatedAt) {
        this(id, "legacy", projectId, null, taskId, "legacy", null, "primary", WorkspaceMode.MANAGED_GIT,
                id, "HEAD", "legacy/" + id, worktreeRef, null, true, state,
                null, 0, "legacy", createdAt, updatedAt);
    }

    public Workspace ready(String locator, String head, Instant at) {
        return copy(WorkspaceState.READY, locator, head, null, at);
    }
    public Workspace fail(String reason, Instant at) {
        return copy(WorkspaceState.FAILED, worktreeRef, headCommit, reason, at);
    }
    public Workspace archive(Instant at) {
        if (state == WorkspaceState.ARCHIVED) return this;
        return copy(WorkspaceState.ARCHIVED, worktreeRef, headCommit, null, at);
    }
    private Workspace copy(WorkspaceState next, String locator, String head, String failure, Instant at) {
        return new Workspace(id, tenantId, projectId, projectDirectoryId, taskId,
                sourceRepositoryId, bridgeId, isolationKey,
                mode, worktreeKey, baseRef, branchName, locator, head, writable, next,
                failure, revision + 1, createdBy, createdAt, at);
    }
}
