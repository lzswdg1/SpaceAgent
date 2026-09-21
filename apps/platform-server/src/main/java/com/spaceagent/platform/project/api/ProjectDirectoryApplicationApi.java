package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.ProjectDirectoryState;

import java.time.Instant;
import java.util.List;

public interface ProjectDirectoryApplicationApi {
    DirectoryView create(CreateCommand command);

    DirectoryView get(Query query);

    List<DirectoryView> list(ListQuery query);

    DirectoryView archive(ArchiveCommand command);

    DirectoryView ensureDefault(EnsureDefaultCommand command);

    DirectoryView resolveConversationDirectory(ResolveConversationCommand command);

    DirectoryView resolveWorkspaceDirectory(ResolveWorkspaceCommand command);

    record CreateCommand(
            String tenantId,
            String userId,
            String projectId,
            String sourceRepositoryId,
            String name,
            String relativePath) {
    }

    record Query(String tenantId, String userId, String projectId, String directoryId) {
    }

    record ListQuery(String tenantId, String userId, String projectId) {
    }

    record ArchiveCommand(String tenantId, String userId, String projectId, String directoryId) {
    }

    record EnsureDefaultCommand(String tenantId, String userId, String projectId) {
    }

    record ResolveConversationCommand(
            String tenantId, String userId, String projectId, String requestedDirectoryId) {
    }

    record ResolveWorkspaceCommand(
            String tenantId, String userId, String projectId,
            String requestedDirectoryId, String sourceRepositoryId) {
    }

    record DirectoryView(
            String id,
            String tenantId,
            String projectId,
            String sourceRepositoryId,
            String name,
            String relativePath,
            boolean defaultDirectory,
            ProjectDirectoryState state,
            Instant createdAt,
            Instant updatedAt) {
    }
}
