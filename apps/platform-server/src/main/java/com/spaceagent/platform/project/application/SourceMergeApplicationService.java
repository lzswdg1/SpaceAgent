package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.SourceMergeApplicationApi;
import com.spaceagent.platform.project.api.WorkspaceSandboxExecutionException;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.SourceMerge;
import com.spaceagent.platform.project.domain.SourceMergeConflictException;
import com.spaceagent.platform.project.domain.SourceMergeGateway;
import com.spaceagent.platform.project.domain.SourceMergeRepository;
import com.spaceagent.platform.project.domain.SourceMergeState;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceRepository;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.project.domain.WorkspaceSandboxGateway;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** Project authority for durable reviewed commit preparation and local integration refs. */
@Service
public class SourceMergeApplicationService implements SourceMergeApplicationApi {
    private final SourceMergeRepository merges;
    private final WorkspaceRepository workspaces;
    private final SourceRepositoryRepository sources;
    private final ProjectAccessPolicy access;
    private final SourceMergeGateway gateway;
    private final WorkspaceSandboxGateway sandbox;
    private final IdGenerator ids;
    private final TimeProvider time;

    public SourceMergeApplicationService(
            SourceMergeRepository merges,
            WorkspaceRepository workspaces,
            SourceRepositoryRepository sources,
            ProjectAccessPolicy access,
            SourceMergeGateway gateway,
            WorkspaceSandboxGateway sandbox,
            IdGenerator ids,
            TimeProvider time) {
        this.merges = merges;
        this.workspaces = workspaces;
        this.sources = sources;
        this.access = access;
        this.gateway = gateway;
        this.sandbox = sandbox;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public SourceMergeView prepare(PrepareCommand command) {
        validateHashes(command.idempotencyHash(), command.inputHash(), command.patchHash());
        requireUuid(command.workspaceId(), "workspaceId");
        requireUuid(command.sourceRepositoryId(), "sourceRepositoryId");
        requireUuid(command.reviewId(), "reviewId");
        requireUuid(command.commitProposalArtifactId(), "commitProposalArtifactId");
        Project project = requireWorkAccess(command.tenantId(), command.userId(), command.projectId());
        Workspace workspace = requireWorkspace(command, project);
        SourceRepository source = requireSource(command, project);
        String expectedBase = commit(command.expectedBaseCommit(), "expectedBaseCommit");
        if (!expectedBase.equals(workspace.headCommit())) {
            throw conflict("MERGE_PROPOSAL_BASE_MISMATCH", "Proposal Base does not match Workspace");
        }
        String commitMessage = bounded(command.commitMessage(), "commitMessage", 500);
        String targetRef = targetRef(workspace.baseRef());

        SourceMerge existing = merges.findByIdempotency(
                command.tenantId(), command.userId(), command.idempotencyHash()).orElse(null);
        if (existing != null) {
            if (!existing.inputHash().equals(command.inputHash())) {
                throw conflict("MERGE_IDEMPOTENCY_CONFLICT",
                        "Idempotency key was reused with different merge input");
            }
            return existing.state() == SourceMergeState.PREPARING
                    || existing.state() == SourceMergeState.UNKNOWN
                    && existing.preparedCommit() == null
                    ? prepareExisting(existing, workspace) : view(existing);
        }

        Instant now = time.now();
        SourceMerge merge = new SourceMerge(
                ids.nextId(), command.tenantId(), command.projectId(), command.taskId(),
                command.sourceRepositoryId(), command.workspaceId(), command.agentRunId(),
                command.reviewId(), command.commitProposalArtifactId(), targetRef,
                expectedBase, command.patchHash(), commitMessage, command.idempotencyHash(),
                command.inputHash(), SourceMergeState.PREPARING, null, null, null, null,
                0, command.userId(), now, now, null, null);
        try {
            merges.insert(merge);
        } catch (DataIntegrityViolationException | IllegalStateException duplicate) {
            SourceMerge concurrent = merges.findByIdempotency(
                    command.tenantId(), command.userId(), command.idempotencyHash())
                    .orElseThrow(() -> duplicate);
            if (!concurrent.inputHash().equals(command.inputHash())) {
                throw conflict("MERGE_IDEMPOTENCY_CONFLICT",
                        "Idempotency key was reused with different merge input");
            }
            merge = concurrent;
        }
        return merge.state() == SourceMergeState.PREPARING
                ? prepareExisting(merge, workspace) : view(merge);
    }

    @Override
    public SourceMergeView get(Query query) {
        requireWorkAccess(query.tenantId(), query.userId(), query.projectId());
        return view(requireMerge(query.tenantId(), query.projectId(), query.mergeId()));
    }

    @Override
    public SourceMergeView apply(ApplyCommand command) {
        requireMergeAccess(command.tenantId(), command.userId(), command.projectId());
        SourceMerge current = requireMerge(command.tenantId(), command.projectId(), command.mergeId());
        if (current.state() == SourceMergeState.APPLIED_LOCAL) return view(current);
        if (current.state() != SourceMergeState.READY) {
            throw conflict("MERGE_NOT_READY", "Source merge must be READY before apply");
        }
        SourceMerge applying = current.transition(
                SourceMergeState.APPLYING, current.preparedCommit(), current.actualTargetCommit(),
                command.governanceApprovalId(), null, time.now());
        cas(applying, current);
        SourceRepository source = requireSource(current.projectId(), current.sourceRepositoryId());
        try {
            SourceMergeGateway.RefUpdate result = gateway.apply(
                    source, current.targetRef(), current.expectedBaseCommit(),
                    current.preparedCommit());
            if (result.status() == SourceMergeGateway.RefUpdateStatus.CONFLICT) {
                return terminal(applying, SourceMergeState.CONFLICT,
                        result.actualTargetCommit(), "MERGE_TARGET_DRIFT");
            }
            return terminal(applying, SourceMergeState.APPLIED_LOCAL,
                    result.actualTargetCommit(), null);
        } catch (RuntimeException error) {
            markUnknown(applying, "MERGE_APPLY_OUTCOME_UNKNOWN");
            throw error;
        }
    }

    @Override
    public SourceMergeView rollback(RollbackCommand command) {
        requireMergeAccess(command.tenantId(), command.userId(), command.projectId());
        SourceMerge current = requireMerge(command.tenantId(), command.projectId(), command.mergeId());
        if (current.state() == SourceMergeState.ROLLED_BACK) return view(current);
        if (current.state() != SourceMergeState.APPLIED_LOCAL) {
            throw conflict("MERGE_NOT_APPLIED", "Only APPLIED_LOCAL merge can be rolled back");
        }
        SourceMerge rolling = current.transition(
                SourceMergeState.ROLLING_BACK, current.preparedCommit(),
                current.actualTargetCommit(), command.governanceApprovalId(), null, time.now());
        cas(rolling, current);
        SourceRepository source = requireSource(current.projectId(), current.sourceRepositoryId());
        try {
            SourceMergeGateway.RefUpdate result = gateway.rollback(
                    source, current.targetRef(), current.preparedCommit(),
                    current.expectedBaseCommit());
            if (result.status() == SourceMergeGateway.RefUpdateStatus.CONFLICT) {
                return terminal(rolling, SourceMergeState.CONFLICT,
                        result.actualTargetCommit(), "MERGE_ROLLBACK_TARGET_DRIFT");
            }
            return terminal(rolling, SourceMergeState.ROLLED_BACK,
                    result.actualTargetCommit(), null);
        } catch (RuntimeException error) {
            markUnknown(rolling, "MERGE_ROLLBACK_OUTCOME_UNKNOWN");
            throw error;
        }
    }

    @Override
    public SourceMergeView reconcile(ReconcileCommand command) {
        requireWorkAccess(command.tenantId(), command.userId(), command.projectId());
        SourceMerge current = requireMerge(command.tenantId(), command.projectId(), command.mergeId());
        if (!java.util.Set.of(
                SourceMergeState.APPLYING, SourceMergeState.ROLLING_BACK,
                SourceMergeState.UNKNOWN, SourceMergeState.CONFLICT).contains(current.state())) {
            return view(current);
        }
        SourceRepository source = requireSource(current.projectId(), current.sourceRepositoryId());
        String actual = gateway.inspect(source, current.targetRef()).actualTargetCommit();
        SourceMergeState next;
        if (actual.equals(current.preparedCommit())) {
            next = SourceMergeState.APPLIED_LOCAL;
        } else if (actual.equals(current.expectedBaseCommit())) {
            boolean rollbackIntent = current.state() == SourceMergeState.ROLLING_BACK
                    || (current.failureCode() != null
                    && current.failureCode().startsWith("MERGE_ROLLBACK"));
            next = rollbackIntent
                    ? SourceMergeState.ROLLED_BACK : SourceMergeState.READY;
        } else {
            next = SourceMergeState.CONFLICT;
        }
        SourceMerge reconciled = current.transition(
                next, current.preparedCommit(), actual, current.governanceApprovalId(),
                next == SourceMergeState.CONFLICT ? "MERGE_TARGET_DRIFT" : null, time.now());
        cas(reconciled, current);
        return view(reconciled);
    }

    private SourceMergeView prepareExisting(SourceMerge merge, Workspace workspace) {
        try {
            WorkspaceSandboxGateway.PreparedCommit prepared = sandbox.prepareCommit(
                    workspace, new WorkspaceSandboxGateway.Execution(
                            merge.agentRunId(), "source-merge:" + merge.id()),
                    merge.expectedBaseCommit(), merge.patchHash(), merge.commitMessage());
            gateway.stagePreparedCommit(
                    requireSource(merge.projectId(), merge.sourceRepositoryId()),
                    workspace.id(),
                    new SourceMergeGateway.PreparedCommitTransfer(
                            prepared.commit(), prepared.bundleReference(),
                            prepared.bundleSha256(), prepared.bundleSizeBytes(),
                            prepared.bundleBytes()));
            SourceMerge ready = merge.transition(
                    SourceMergeState.READY, prepared.commit(), merge.actualTargetCommit(),
                    null, null, time.now());
            cas(ready, merge);
            return view(ready);
        } catch (SourceMergeConflictException conflict) {
            SourceMerge failed = merge.transition(
                    SourceMergeState.CONFLICT, merge.preparedCommit(), merge.actualTargetCommit(),
                    null, conflict.code(), time.now());
            cas(failed, merge);
            throw new BusinessException(conflict.getMessage(), HttpStatus.CONFLICT, conflict.code());
        } catch (WorkspaceSandboxExecutionException failure) {
            if (failure.ambiguous()) {
                markUnknown(merge, "MERGE_PREPARE_OUTCOME_UNKNOWN");
                throw failure;
            }
            SourceMerge failed = merge.transition(
                    SourceMergeState.CONFLICT, merge.preparedCommit(), merge.actualTargetCommit(),
                    null, failure.safeCode(), time.now());
            cas(failed, merge);
            throw new BusinessException(
                    "Sandbox rejected merge preparation", HttpStatus.CONFLICT,
                    failure.safeCode());
        } catch (RuntimeException error) {
            markUnknown(merge, "MERGE_PREPARE_OUTCOME_UNKNOWN");
            throw error;
        }
    }

    private SourceMergeView terminal(
            SourceMerge current,
            SourceMergeState state,
            String actual,
            String failureCode) {
        SourceMerge terminal = current.transition(
                state, current.preparedCommit(), actual, current.governanceApprovalId(),
                failureCode, time.now());
        if (!merges.update(terminal, current.revision(), current.state())) {
            throw conflict("MERGE_COMPLETION_AMBIGUOUS",
                    "Git result could not be committed; reconcile is required");
        }
        return view(terminal);
    }

    private void markUnknown(SourceMerge current, String code) {
        SourceMerge unknown = current.transition(
                SourceMergeState.UNKNOWN, current.preparedCommit(), current.actualTargetCommit(),
                current.governanceApprovalId(), code, time.now());
        merges.update(unknown, current.revision(), current.state());
    }

    private void cas(SourceMerge next, SourceMerge current) {
        if (!merges.update(next, current.revision(), current.state())) {
            throw conflict("MERGE_REVISION_CONFLICT", "Source merge changed concurrently");
        }
    }

    private Project requireWorkAccess(String tenantId, String userId, String projectId) {
        Project project = access.requireProject(tenantId, userId, projectId);
        access.requireRole(project, userId, ProjectRole::canWorkOnTasks);
        return project;
    }

    private Project requireMergeAccess(String tenantId, String userId, String projectId) {
        Project project = access.requireProject(tenantId, userId, projectId);
        access.requireRole(project, userId, ProjectRole::canModify);
        return project;
    }

    private Workspace requireWorkspace(PrepareCommand command, Project project) {
        return workspaces.findById(command.workspaceId())
                .filter(value -> value.tenantId().equals(command.tenantId()))
                .filter(value -> value.projectId().equals(project.id()))
                .filter(value -> value.taskId().equals(command.taskId()))
                .filter(value -> value.sourceRepositoryId().equals(command.sourceRepositoryId()))
                .filter(value -> value.mode() == WorkspaceMode.MANAGED_GIT)
                .filter(value -> value.state() == WorkspaceState.READY && value.writable())
                .orElseThrow(() -> conflict(
                        "MERGE_WORKSPACE_SCOPE_MISMATCH", "Managed Workspace scope mismatch"));
    }

    private SourceRepository requireSource(PrepareCommand command, Project project) {
        SourceRepository source = requireSource(project.id(), command.sourceRepositoryId());
        if (!source.tenantId().equals(command.tenantId())) {
            throw conflict("MERGE_SOURCE_SCOPE_MISMATCH", "Source Repository scope mismatch");
        }
        return source;
    }

    private SourceRepository requireSource(String projectId, String sourceId) {
        return sources.findById(sourceId)
                .filter(value -> value.projectId().equals(projectId))
                .filter(value -> value.type() == SourceRepositoryType.GITHUB)
                .filter(value -> value.state() == SourceRepositoryState.READY)
                .orElseThrow(() -> conflict(
                        "MERGE_SOURCE_NOT_READY", "READY managed Git source not found"));
    }

    private SourceMerge requireMerge(String tenantId, String projectId, String mergeId) {
        requireUuid(mergeId, "mergeId");
        return merges.findById(mergeId)
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.projectId().equals(projectId))
                .orElseThrow(() -> new BusinessException(
                        "Source merge not found", HttpStatus.NOT_FOUND,
                        "SOURCE_MERGE_NOT_FOUND"));
    }

    private static SourceMergeView view(SourceMerge value) {
        return new SourceMergeView(
                value.id(), value.projectId(), value.taskId(), value.sourceRepositoryId(),
                value.workspaceId(), value.agentRunId(), value.reviewId(),
                value.commitProposalArtifactId(), value.targetRef(),
                value.expectedBaseCommit(), value.patchHash(), value.state(),
                value.preparedCommit(), value.actualTargetCommit(),
                value.governanceApprovalId(), value.failureCode(), value.revision(), false,
                "REMOTE_NOT_UPDATED; retrieve the reviewed Commit Proposal "
                        + value.commitProposalArtifactId()
                        + " and apply its Patch Artifact through a trusted client",
                value.createdAt(), value.updatedAt(), value.appliedAt(), value.rolledBackAt());
    }

    private static String targetRef(String selectedBranch) {
        String branch = bounded(selectedBranch, "baseRef", 200);
        if (branch.startsWith("refs/heads/")) branch = branch.substring("refs/heads/".length());
        if (branch.matches("[0-9a-fA-F]{40,64}")) {
            throw conflict("MERGE_TARGET_REF_INVALID", "A merge requires an explicit named target branch");
        }
        if (!branch.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}")
                || branch.startsWith("refs/") || branch.contains("..") || branch.contains("//")
                || branch.contains("@{") || branch.contains("~")
                || branch.contains("^") || branch.contains(":") || branch.contains("\\")
                || branch.contains(" ") || branch.endsWith(".") || branch.endsWith("/")
                || branch.endsWith(".lock")
                || java.util.Arrays.stream(branch.split("/")).anyMatch(value -> value.startsWith("."))) {
            throw conflict("MERGE_TARGET_REF_INVALID", "Selected target branch is not a safe ref");
        }
        return "refs/heads/" + branch;
    }

    private static String commit(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{40,64}")) {
            throw new IllegalArgumentException(field + " must be a Git object id");
        }
        return normalized;
    }

    private static void validateHashes(String... values) {
        for (String value : values) {
            if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Merge hashes must be SHA-256 values");
            }
        }
    }

    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + max);
        }
        return value.trim();
    }

    private static void requireUuid(String value, String field) {
        try {
            UUID.fromString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }
}
