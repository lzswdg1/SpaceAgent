package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectWorkbenchApplicationService implements ProjectWorkbenchApplicationApi {
    private final WorkspaceApplicationApi workspaces;
    private final SourceRepositoryApplicationApi sources;
    private final WorkspaceToolApplicationApi tools;
    private final WorkspaceProvisioningGateway git;
    public ProjectWorkbenchApplicationService(WorkspaceApplicationApi workspaces, SourceRepositoryApplicationApi sources,
            WorkspaceToolApplicationApi tools, WorkspaceProvisioningGateway git) {
        this.workspaces=workspaces; this.sources=sources; this.tools=tools; this.git=git;
    }
    public List<String> branches(SourceQuery query) {
        // The Project owner validates membership and source ownership before any filesystem access.
        var source = sources.get(new GetSourceRepositoryQuery(query.tenantId(), query.userId(), query.projectId(), query.sourceId()));
        return git.branches(source.id()).stream().filter(RepositoryBranchVisibility::visible).toList();
    }
    public Environment environment(Query query) {
        var workspace = workspace(query);
        var source = sources.get(new GetSourceRepositoryQuery(query.tenantId(), query.userId(), query.projectId(), workspace.sourceRepositoryId()));
        if (workspace.state()!=WorkspaceState.READY) throw new BusinessException("Workspace is not ready", HttpStatus.CONFLICT, "WORKSPACE_NOT_READY");
        var snapshot = tools.git(new WorkspaceToolApplicationApi.GitQuery(
                scope(query, workspace), 800_000));
        String baseRef = RepositoryBranchVisibility.publicRef(workspace.baseRef());
        return new Environment(workspace.id(), source.displayName(), baseRef, baseRef,
                snapshot.headCommit(), workspace.state().name(), snapshot.status(), snapshot.patch(),
                snapshot.changedFiles(), snapshot.truncated());
    }
    public WorkspaceToolApplicationApi.FileListView files(Query query, String path) {
        checkPath(path);
        var result=tools.listFiles(new WorkspaceToolApplicationApi.FileListQuery(scope(query), path, 1, 300));
        String prefix = ".".equals(path) ? "" : path.replaceAll("/+$", "") + "/";
        return new WorkspaceToolApplicationApi.FileListView(result.root(), result.entries().stream()
                .filter(entry -> !metadata(entry.path()) && entry.path().startsWith(prefix)
                        && !entry.path().substring(prefix.length()).contains("/")).toList(), result.truncated());
    }
    public WorkspaceToolApplicationApi.FileReadView file(Query query, String path) {
        checkPath(path);
        return tools.readFile(new WorkspaceToolApplicationApi.FileReadQuery(scope(query), path, 200_000));
    }
    private WorkspaceApplicationApi.WorkspaceView workspace(Query query) {
        return workspaces.get(new WorkspaceApplicationApi.Query(query.tenantId(), query.userId(), query.projectId(), query.workspaceId()));
    }
    private WorkspaceToolApplicationApi.Scope scope(Query query) {
        var workspace=workspace(query);
        return scope(query, workspace);
    }
    private WorkspaceToolApplicationApi.Scope scope(
            Query query, WorkspaceApplicationApi.WorkspaceView workspace) {
        return new WorkspaceToolApplicationApi.Scope(query.tenantId(), query.userId(), query.projectId(), workspace.taskId(),
                workspace.id(), "inspection:" + UUID.randomUUID(), "file-inspection");
    }
    private static boolean metadata(String path) { return java.util.Arrays.asList(path.replace('\\','/').split("/")).contains(".git"); }
    private static void checkPath(String path) {
        if(path==null || path.length()>500 || metadata(path) || path.startsWith("/") || path.contains("\\")
                || java.util.Arrays.asList(path.split("/")).contains("..")) {
            throw new BusinessException("Invalid repository file path", HttpStatus.BAD_REQUEST, "WORKSPACE_FILE_PATH_INVALID");
        }
    }
}
