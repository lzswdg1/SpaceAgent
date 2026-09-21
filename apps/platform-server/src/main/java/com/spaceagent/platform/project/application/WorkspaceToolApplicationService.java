package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.WorkspaceToolApplicationApi;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceRepository;
import com.spaceagent.platform.project.domain.WorkspaceSandboxGateway;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WorkspaceToolApplicationService implements WorkspaceToolApplicationApi {
    private final WorkspaceRepository workspaces;
    private final ProjectAccessPolicy access;
    private final WorkspaceSandboxGateway sandbox;

    public WorkspaceToolApplicationService(
            WorkspaceRepository workspaces,
            ProjectAccessPolicy access,
            WorkspaceSandboxGateway sandbox) {
        this.workspaces = workspaces;
        this.access = access;
        this.sandbox = sandbox;
    }

    @Override
    public FileReadView readFile(FileReadQuery query) {
        Workspace workspace = require(query.scope());
        var value = sandbox.readFile(workspace, execution(query.scope()),
                query.path(), query.maxCharacters());
        return new FileReadView(
                value.path(), value.content(), value.sizeBytes(), value.truncated());
    }

    @Override
    public FileListView listFiles(FileListQuery query) {
        Workspace workspace = require(query.scope());
        var value = sandbox.listFiles(workspace, execution(query.scope()),
                query.path(), query.maxDepth(), query.maxEntries());
        return new FileListView(
                value.root(), value.entries().stream()
                .map(entry -> new FileEntryView(
                        entry.path(), entry.directory(), entry.sizeBytes()))
                .toList(), value.truncated());
    }

    @Override
    public GitView git(GitQuery query) {
        Workspace workspace = require(query.scope());
        WorkspaceSandboxGateway.Snapshot snapshot = sandbox.snapshot(
                workspace, execution(query.scope()), query.maxCharacters());
        int limit = Math.max(1_000, Math.min(query.maxCharacters(), 200_000));
        boolean truncated = snapshot.patch().length() > limit;
        String patch = truncated ? snapshot.patch().substring(0, limit) : snapshot.patch();
        return new GitView(
                snapshot.headCommit(), snapshot.status(), patch,
                snapshot.changedFiles(), truncated);
    }

    @Override
    public DocumentView readDocument(DocumentReadQuery query) {
        Workspace workspace = require(query.scope());
        var value = sandbox.readDocument(workspace, execution(query.scope()),
                query.path(), query.maxCharacters());
        return new DocumentView(
                value.path(), value.mediaType(), value.content(),
                value.metadata(), value.truncated());
    }

    @Override
    public DocumentWriteView writeDocument(DocumentWriteCommand command) {
        Workspace workspace = require(command.scope());
        var value = sandbox.writeDocument(workspace, execution(command.scope()),
                command.path(), command.content(), command.format());
        return new DocumentWriteView(
                value.path(), value.format(), value.sizeBytes(), value.changedFiles());
    }

    private Workspace require(Scope scope) {
        if (scope == null) throw missing();
        Project project = access.requireProject(
                scope.tenantId(), scope.userId(), scope.projectId());
        access.requireRole(project, scope.userId(), ProjectRole::canWorkOnTasks);
        try {
            UUID.fromString(scope.workspaceId());
        } catch (RuntimeException error) {
            throw missing();
        }
        return workspaces.findById(scope.workspaceId())
                .filter(workspace -> workspace.tenantId().equals(scope.tenantId()))
                .filter(workspace -> workspace.projectId().equals(scope.projectId()))
                .filter(workspace -> workspace.taskId().equals(scope.taskId()))
                .filter(workspace -> workspace.mode() == WorkspaceMode.MANAGED_GIT)
                .filter(workspace -> workspace.state() == WorkspaceState.READY)
                .filter(Workspace::writable)
                .orElseThrow(WorkspaceToolApplicationService::missing);
    }

    private static WorkspaceSandboxGateway.Execution execution(Scope scope) {
        return new WorkspaceSandboxGateway.Execution(scope.agentRunId(), scope.toolCallId());
    }

    private static BusinessException missing() {
        return new BusinessException(
                "READY managed Workspace not found", HttpStatus.NOT_FOUND,
                "WORKSPACE_TOOL_SCOPE_NOT_FOUND");
    }
}
