package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.project.domain.ProjectReconciliationEvidenceVerifier;
import com.spaceagent.platform.project.domain.ProjectReconciliationStep;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Integration adapter for Project's evidence-verification port; it owns no reconciliation state. */
@Component
public class ProjectReconciliationEvidenceVerifierAdapter implements ProjectReconciliationEvidenceVerifier {
    private final ArtifactApplicationApi artifacts;
    private final MultiAgentCollaborationApplicationApi collaboration;
    public ProjectReconciliationEvidenceVerifierAdapter(ArtifactApplicationApi artifacts, MultiAgentCollaborationApplicationApi collaboration) { this.artifacts=artifacts; this.collaboration=collaboration; }
    @Override public void verify(String tenant, String owner, ProjectReconciliationStep step, ProjectReconciliationStep.ResolutionEvidence e) {
        var patch=artifact(tenant,e.patchArtifactId(),ArtifactType.PATCH); var commit=artifact(tenant,e.commitProposalArtifactId(),ArtifactType.COMMIT_PROPOSAL); var test=artifact(tenant,e.testReportArtifactId(),ArtifactType.TEST_REPORT);
        if(!step.projectId().equals(patch.projectId())||!patch.projectId().equals(commit.projectId())||!patch.projectId().equals(test.projectId())||!patch.taskId().equals(commit.taskId())||!patch.taskId().equals(test.taskId())) throw conflict("PROJECT_RECONCILIATION_ARTIFACT_SCOPE_INVALID");
        collaboration.review(owner,e.reviewId()).filter(v->v.decision()==AgentReviewDecision.APPROVED).filter(v->v.artifactIds().containsAll(java.util.List.of(patch.id(),commit.id(),test.id()))).orElseThrow(()->conflict("PROJECT_RECONCILIATION_REVIEW_INVALID"));
    }
    private ArtifactApplicationApi.ArtifactView artifact(String tenant,String id,ArtifactType type){return artifacts.find(tenant,id).filter(v->v.type()==type).orElseThrow(()->conflict("PROJECT_RECONCILIATION_ARTIFACT_INVALID"));}
    private static BusinessException conflict(String code){return new BusinessException("Project reconciliation evidence is invalid",HttpStatus.CONFLICT,code);}
}
