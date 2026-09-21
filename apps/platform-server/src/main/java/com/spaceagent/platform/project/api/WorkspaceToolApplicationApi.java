package com.spaceagent.platform.project.api;

import java.util.List;
import java.util.Map;

public interface WorkspaceToolApplicationApi {
    FileReadView readFile(FileReadQuery query);

    FileListView listFiles(FileListQuery query);

    GitView git(GitQuery query);

    DocumentView readDocument(DocumentReadQuery query);

    DocumentWriteView writeDocument(DocumentWriteCommand command);

    record Scope(
            String tenantId,
            String userId,
            String projectId,
            String taskId,
            String workspaceId,
            String agentRunId,
            String toolCallId) {
        public Scope(
                String tenantId, String userId, String projectId,
                String taskId, String workspaceId) {
            this(tenantId, userId, projectId, taskId, workspaceId, null, null);
        }
    }

    record FileReadQuery(Scope scope, String path, int maxCharacters) {
    }

    record FileListQuery(Scope scope, String path, int maxDepth, int maxEntries) {
    }

    record GitQuery(Scope scope, int maxCharacters) {
    }

    record DocumentReadQuery(Scope scope, String path, int maxCharacters) {
    }

    record DocumentWriteCommand(
            Scope scope, String path, String content, String format) {
    }

    record FileReadView(
            String path, String content, long sizeBytes, boolean truncated) {
    }

    record FileEntryView(String path, boolean directory, long sizeBytes) {
    }

    record FileListView(
            String root, List<FileEntryView> entries, boolean truncated) {
        public FileListView {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    record GitView(
            String headCommit,
            String status,
            String patch,
            List<String> changedFiles,
            boolean truncated) {
        public GitView {
            changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        }
    }

    record DocumentView(
            String path,
            String mediaType,
            String content,
            Map<String, String> metadata,
            boolean truncated) {
        public DocumentView {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record DocumentWriteView(
            String path, String format, long sizeBytes, List<String> changedFiles) {
        public DocumentWriteView {
            changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        }
    }
}
