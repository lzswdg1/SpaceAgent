package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.integration.api.ProjectReconciliationIntegrationApi;
import com.spaceagent.platform.project.api.ProjectReconciliationApplicationApi;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.ProjectReconciliationStepState;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Integration composes public owner APIs; Project remains reconciliation authority and Runtime is projection-only. */
@Service
public class ProjectReconciliationIntegrationService implements ProjectReconciliationIntegrationApi {
    private final ProjectReconciliationApplicationApi reconciliations;
    private final WorkspaceApplicationApi workspaces;
    private final ProjectPlanExecutionApplicationApi executions;
    private final ArtifactApplicationApi artifacts;
    private final MultiAgentCollaborationApplicationApi collaboration;
    private final IdGenerator ids;
    private final com.spaceagent.platform.project.api.SourceMergeApplicationApi merges;
    public ProjectReconciliationIntegrationService(ProjectReconciliationApplicationApi reconciliations,
            WorkspaceApplicationApi workspaces, ProjectPlanExecutionApplicationApi executions,
            ArtifactApplicationApi artifacts, MultiAgentCollaborationApplicationApi collaboration, IdGenerator ids,
            com.spaceagent.platform.project.api.SourceMergeApplicationApi merges) {
        this.reconciliations=reconciliations; this.workspaces=workspaces; this.executions=executions;
        this.artifacts=artifacts; this.collaboration=collaboration; this.ids=ids;
        this.merges=merges;
    }
    @Override public ProjectReconciliationApplicationApi.ReconciliationStepView onBaseDrift(BaseDriftCommand c) {
        var step=reconciliations.createForBaseDrift(new ProjectReconciliationApplicationApi.CreateForBaseDriftCommand(c.tenantId(),c.userId(),c.ownerUserId(),c.projectId(),c.projectDirectoryId(),c.taskPlanId(),c.planStepId(),c.executionId(),c.barrierId(),c.sourceMergeId(),c.expectedBaseCommit(),c.actualBaseCommit(),c.originalPatchArtifactId(),c.originalCommitProposalArtifactId(),c.originalTestReportArtifactId(),c.originalReviewId()));
        if(step.state()==ProjectReconciliationStepState.WAITING_RECONCILIATION){
            var target=merges.get(new com.spaceagent.platform.project.api.SourceMergeApplicationApi.Query(c.tenantId(),c.userId(),c.projectId(),c.sourceMergeId())).targetRef();
            var workspace=workspaces.provision(new WorkspaceApplicationApi.ProvisionCommand(c.tenantId(),c.userId(),c.projectId(),c.projectDirectoryId(),c.taskId(),c.sourceRepositoryId(),target,"reconciliation:"+step.id()));
            step=reconciliations.recordProposal(new ProjectReconciliationApplicationApi.RecordProposalCommand(c.tenantId(),c.userId(),c.projectId(),step.id(),step.revision(),workspace.id(),ids.nextId(),c.actualBaseCommit()));
        }
        var execution=executions.get(new ProjectPlanExecutionApplicationApi.Query(c.tenantId(),c.ownerUserId(),c.projectId(),c.taskPlanId(),c.executionId()));
        if(execution.state()!=com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.BLOCKED) executions.waitForReconciliation(new ProjectPlanExecutionApplicationApi.ReconciliationWaitCommand(c.tenantId(),c.ownerUserId(),c.projectId(),c.taskPlanId(),c.executionId(),execution.revision(),step.id()));
        return step;
    }
    @Override public ProjectReconciliationApplicationApi.ReconciliationStepView resolveAndResume(ResolveCommand c) {
        evidence(c); var step=reconciliations.resolve(new ProjectReconciliationApplicationApi.ResolveCommand(c.tenantId(),c.userId(),c.projectId(),c.reconciliationStepId(),c.expectedRevision(),new ProjectReconciliationApplicationApi.ResolutionEvidence(c.patchArtifactId(),c.commitProposalArtifactId(),c.testReportArtifactId(),c.reviewId(),c.sourceMergeId(),c.expectedBaseCommit(),c.actualBaseCommit())));
        if(step.state()!=ProjectReconciliationStepState.RESOLVED) throw conflict("PROJECT_RECONCILIATION_NOT_RESOLVED");
        var execution=executions.get(new ProjectPlanExecutionApplicationApi.Query(c.tenantId(),step.ownerUserId(),c.projectId(),step.taskPlanId(),step.executionId()));
        executions.resumeAfterReconciliation(new ProjectPlanExecutionApplicationApi.ReconciliationResumeCommand(c.tenantId(),step.ownerUserId(),c.projectId(),step.taskPlanId(),step.executionId(),execution.revision(),step.id())); return step;
    }
    private void evidence(ResolveCommand c){
        var patch=artifact(c.tenantId(),c.patchArtifactId(),ArtifactType.PATCH); var commit=artifact(c.tenantId(),c.commitProposalArtifactId(),ArtifactType.COMMIT_PROPOSAL); var test=artifact(c.tenantId(),c.testReportArtifactId(),ArtifactType.TEST_REPORT);
        if(!patch.projectId().equals(commit.projectId())||!patch.projectId().equals(test.projectId())||!patch.taskId().equals(commit.taskId())||!patch.taskId().equals(test.taskId())) throw conflict("PROJECT_RECONCILIATION_ARTIFACT_SCOPE_INVALID");
        var review=collaboration.review(c.userId(),c.reviewId()).filter(v->v.decision()==AgentReviewDecision.APPROVED).filter(v->v.artifactIds().containsAll(java.util.List.of(patch.id(),commit.id(),test.id()))).orElseThrow(()->conflict("PROJECT_RECONCILIATION_REVIEW_INVALID"));
    }
    private ArtifactApplicationApi.ArtifactView artifact(String tenant,String id,ArtifactType type){return artifacts.find(tenant,id).filter(v->v.type()==type).orElseThrow(()->conflict("PROJECT_RECONCILIATION_ARTIFACT_INVALID"));}
    private static BusinessException conflict(String code){return new BusinessException("Project reconciliation evidence is invalid",HttpStatus.CONFLICT,code);}
}
