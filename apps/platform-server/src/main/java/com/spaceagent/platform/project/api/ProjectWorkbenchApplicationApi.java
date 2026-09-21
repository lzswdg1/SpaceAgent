package com.spaceagent.platform.project.api;

import java.util.List;

/** Authenticated read surface for the optional repository browser. */
public interface ProjectWorkbenchApplicationApi {
    List<String> branches(SourceQuery query);
    Environment environment(Query query);
    WorkspaceToolApplicationApi.FileListView files(Query query, String path);
    WorkspaceToolApplicationApi.FileReadView file(Query query, String path);
    record SourceQuery(String tenantId, String userId, String projectId, String sourceId) {}
    record Query(String tenantId, String userId, String projectId, String workspaceId) {}
    record Environment(String workspaceId, String sourceName, String baseRef, String branch,
                       String headCommit, String state, String status, String patch,
                       List<String> changedFiles, boolean truncated) {}
}
