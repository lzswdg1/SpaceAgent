package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.ProjectReconciliationApplicationApi;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Project is the sole authority that writes SourceMerge reconciliation lifecycle state. */
@Service
public class ProjectReconciliationApplicationService implements ProjectReconciliationApplicationApi {
    private final ProjectReconciliationStepRepository steps;
    private final SourceMergeRepository merges;
    private final WorkspaceRepository workspaces;
    private final ProjectDirectoryRepository directories;
    private final TaskPlanRepository plans;
    private final ProjectAccessPolicy access;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final ProjectReconciliationEvidenceVerifier evidenceVerifier;

    public ProjectReconciliationApplicationService(ProjectReconciliationStepRepository steps,
            SourceMergeRepository merges, WorkspaceRepository workspaces,
            ProjectDirectoryRepository directories, TaskPlanRepository plans,
            ProjectAccessPolicy access, IdGenerator ids, TimeProvider time,
            ProjectReconciliationEvidenceVerifier evidenceVerifier) {
        this.steps = steps; this.merges = merges; this.workspaces = workspaces;
        this.directories = directories; this.plans = plans; this.access = access;
        this.ids = ids; this.time = time;
        this.evidenceVerifier = evidenceVerifier;
    }

    @Override public ReconciliationStepView createForBaseDrift(CreateForBaseDriftCommand c) {
        Project project = work(c.tenantId(), c.userId(), c.projectId());
        SourceMerge merge = merges.findById(c.sourceMergeId())
                .filter(v -> v.tenantId().equals(c.tenantId()) && v.projectId().equals(project.id()))
                .filter(v -> v.state() == SourceMergeState.CONFLICT)
                .orElseThrow(() -> conflict("PROJECT_RECONCILIATION_SOURCE_MERGE_INVALID"));
        if (!merge.expectedBaseCommit().equals(c.expectedBaseCommit())
                || !merge.actualTargetCommit().equals(c.actualBaseCommit())) {
            throw conflict("PROJECT_RECONCILIATION_BASE_EVIDENCE_INVALID");
        }
        directories.findById(c.projectDirectoryId()).filter(v -> v.projectId().equals(project.id()))
                .orElseThrow(() -> conflict("PROJECT_RECONCILIATION_DIRECTORY_INVALID"));
        TaskPlan plan = plans.findById(c.taskPlanId()).filter(v -> project.id().equals(v.projectId()))
                .orElseThrow(() -> conflict("PROJECT_RECONCILIATION_PLAN_INVALID"));
        boolean stepMatches = plans.findSteps(plan.id()).stream().anyMatch(v ->
                v.id().equals(c.planStepId()) && project.id().equals(v.projectId()));
        if (!stepMatches) throw conflict("PROJECT_RECONCILIATION_STEP_INVALID");
        return steps.findBySourceMergeId(c.tenantId(), c.ownerUserId(), project.id(), merge.id())
                .map(ProjectReconciliationApplicationService::view).orElseGet(() -> {
                    ProjectReconciliationStep created = ProjectReconciliationStep.forBaseDrift(ids.nextId(),
                            c.tenantId(), c.ownerUserId(), project.id(), c.projectDirectoryId(), plan.id(),
                            c.planStepId(), c.executionId(), c.barrierId(), merge.id(), c.expectedBaseCommit(),
                            c.actualBaseCommit(), c.originalPatchArtifactId(), c.originalCommitProposalArtifactId(),
                            c.originalTestReportArtifactId(), c.originalReviewId(), time.now());
                    steps.insert(created); return view(created);
                });
    }

    @Override public ReconciliationStepView get(GetQuery q) { return view(scoped(q.tenantId(), q.userId(), q.projectId(), q.reconciliationStepId())); }

    @Override public ReconciliationStepView recordProposal(RecordProposalCommand c) {
        ProjectReconciliationStep current = scoped(c.tenantId(), c.userId(), c.projectId(), c.reconciliationStepId());
        checkRevision(current, c.expectedRevision());
        Workspace workspace = workspaces.findById(c.workspaceId())
                .filter(v -> v.projectId().equals(current.projectId()))
                .filter(v -> current.projectDirectoryId().equals(v.projectDirectoryId()))
                .filter(v -> v.state() == WorkspaceState.READY && v.writable())
                .orElseThrow(() -> conflict("PROJECT_RECONCILIATION_WORKSPACE_INVALID"));
        SourceMerge original = merges.findById(current.sourceMergeId()).orElseThrow(() -> conflict("PROJECT_RECONCILIATION_SOURCE_MERGE_INVALID"));
        if (workspace.id().equals(original.workspaceId()) || !c.baseCommit().equals(current.actualBaseCommit()))
            throw conflict("PROJECT_RECONCILIATION_WORKSPACE_SCOPE_INVALID");
        ProjectReconciliationStep next = current.recordProposal(new ProjectReconciliationStep.ReconciliationProposal(
                workspace.id(), c.proposalId(), c.baseCommit()), time.now());
        update(next, current); return view(next);
    }

    @Override public ReconciliationStepView resolve(ResolveCommand c) {
        ProjectReconciliationStep current = scoped(c.tenantId(), c.userId(), c.projectId(), c.reconciliationStepId());
        checkRevision(current, c.expectedRevision());
        ResolutionEvidence e = c.evidence();
        evidenceVerifier.verify(c.tenantId(), c.userId(), current, new ProjectReconciliationStep.ResolutionEvidence(
                e.patchArtifactId(), e.commitProposalArtifactId(), e.testReportArtifactId(), e.reviewId(),
                e.sourceMergeId(), e.expectedBaseCommit(), e.actualBaseCommit()));
        SourceMerge merge = merges.findById(e.sourceMergeId())
                .filter(v -> v.projectId().equals(current.projectId()))
                .filter(v -> v.state() == SourceMergeState.APPLIED_LOCAL)
                .filter(v -> e.expectedBaseCommit().equals(v.expectedBaseCommit()))
                .filter(v -> e.actualBaseCommit().equals(v.actualTargetCommit()))
                .orElseThrow(() -> conflict("PROJECT_RECONCILIATION_CAS_EVIDENCE_INVALID"));
        ProjectReconciliationStep next = current.resolve(new ProjectReconciliationStep.ResolutionEvidence(
                e.patchArtifactId(), e.commitProposalArtifactId(), e.testReportArtifactId(), e.reviewId(),
                merge.id(), e.expectedBaseCommit(), e.actualBaseCommit()), time.now());
        update(next, current); return view(next);
    }

    @Override public ReconciliationStepView blockUnknown(BlockUnknownCommand c) {
        ProjectReconciliationStep current = scoped(c.tenantId(), c.userId(), c.projectId(), c.reconciliationStepId());
        checkRevision(current, c.expectedRevision());
        ProjectReconciliationStep next = current.blockUnknown(time.now()); update(next, current); return view(next);
    }

    private ProjectReconciliationStep scoped(String tenant, String user, String project, String id) {
        work(tenant, user, project);
        return steps.findById(tenant, user, project, id).orElseThrow(() -> conflict("PROJECT_RECONCILIATION_NOT_FOUND"));
    }
    private Project work(String tenant, String user, String project) {
        Project value = access.requireProject(tenant, user, project); access.requireActive(value);
        access.requireRole(value, user, ProjectRole::canWorkOnTasks); return value;
    }
    private void checkRevision(ProjectReconciliationStep value, long expected) { if (expected < 1 || value.revision() != expected) throw conflict("PROJECT_RECONCILIATION_STALE_REVISION"); }
    private void update(ProjectReconciliationStep next, ProjectReconciliationStep current) { if (!steps.update(next, current.revision(), current.state())) throw conflict("PROJECT_RECONCILIATION_STALE_REVISION"); }
    private static BusinessException conflict(String code) { return new BusinessException("Project reconciliation conflict", HttpStatus.CONFLICT, code); }
    private static ReconciliationStepView view(ProjectReconciliationStep v) {
        var p=v.proposal(); var r=v.resolution(); return new ReconciliationStepView(v.id(),v.tenantId(),v.ownerUserId(),v.projectId(),v.projectDirectoryId(),v.taskPlanId(),v.planStepId(),v.executionId(),v.barrierId(),v.sourceMergeId(),v.expectedBaseCommit(),v.actualBaseCommit(),v.originalPatchArtifactId(),v.originalCommitProposalArtifactId(),v.originalTestReportArtifactId(),v.originalReviewId(),v.state(),p==null?null:p.workspaceId(),p==null?null:p.proposalId(),p==null?null:p.baseCommit(),r==null?null:new ResolutionEvidence(r.patchArtifactId(),r.commitProposalArtifactId(),r.testReportArtifactId(),r.reviewId(),r.sourceMergeId(),r.expectedBaseCommit(),r.actualBaseCommit()),v.blockedCode(),v.revision(),v.createdAt(),v.updatedAt(),v.resolvedAt());
    }
}
