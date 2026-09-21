package com.spaceagent.platform.integration;

import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.integration.api.ProjectReconciliationIntegrationApi;
import com.spaceagent.platform.integration.application.ProjectReconciliationIntegrationService;
import com.spaceagent.platform.project.api.ProjectReconciliationApplicationApi;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.ProjectReconciliationStepState;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectReconciliationIntegrationServiceTest {
    private static final String ID="00000000-0000-4000-8000-000000000001";
    private static final String PROJECT="00000000-0000-4000-8000-000000000002";
    private static final String DIRECTORY="00000000-0000-4000-8000-000000000003";
    private static final String PLAN="00000000-0000-4000-8000-000000000004";
    private static final String STEP="00000000-0000-4000-8000-000000000005";
    private static final String EXECUTION="00000000-0000-4000-8000-000000000006";
    private static final String SOURCE="00000000-0000-4000-8000-000000000007";
    private static final String MERGE="00000000-0000-4000-8000-000000000008";
    private static final String BASE="a".repeat(40), ACTUAL="b".repeat(40), RESULT="c".repeat(40);
    private static final Instant NOW=Instant.parse("2026-09-08T00:00:00Z");

    @Test void driftCreatesProjectStepThenIsolatesWorkspaceAndBlocksRuntime() {
        var reconciliations=mock(ProjectReconciliationApplicationApi.class); var workspaces=mock(WorkspaceApplicationApi.class); var executions=mock(ProjectPlanExecutionApplicationApi.class);
        when(reconciliations.createForBaseDrift(any())).thenReturn(view(ProjectReconciliationStepState.WAITING_RECONCILIATION,1));
        when(workspaces.provision(any())).thenReturn(workspace());
        when(reconciliations.recordProposal(any())).thenReturn(view(ProjectReconciliationStepState.PROPOSAL_READY,2));
        when(executions.get(any())).thenReturn(execution(ProjectPlanExecutionState.RUNNING,4));
        var service=service(reconciliations,workspaces,executions);
        var result=service.onBaseDrift(new ProjectReconciliationIntegrationApi.BaseDriftCommand("tenant","owner","owner",PROJECT,DIRECTORY,"task",PLAN,STEP,EXECUTION,EXECUTION,MERGE,SOURCE,BASE,ACTUAL,id(),id(),id(),id()));
        assertThat(result.state()).isEqualTo(ProjectReconciliationStepState.PROPOSAL_READY);
        var provision=org.mockito.ArgumentCaptor.forClass(WorkspaceApplicationApi.ProvisionCommand.class); verify(workspaces).provision(provision.capture());
        assertThat(provision.getValue().baseRef()).isEqualTo("refs/heads/feature/selected"); assertThat(provision.getValue().isolationKey()).isEqualTo("reconciliation:"+ID);
        verify(executions).waitForReconciliation(any(ProjectPlanExecutionApplicationApi.ReconciliationWaitCommand.class));
    }

    @Test void completeEvidenceResolvesProjectBeforeExplicitRuntimeResume() {
        var reconciliations=mock(ProjectReconciliationApplicationApi.class); var workspaces=mock(WorkspaceApplicationApi.class); var executions=mock(ProjectPlanExecutionApplicationApi.class);
        var artifacts=mock(ArtifactApplicationApi.class); var collaboration=mock(MultiAgentCollaborationApplicationApi.class);
        when(artifacts.find("tenant", "patch")).thenReturn(Optional.of(artifact("patch",ArtifactType.PATCH)));
        when(artifacts.find("tenant", "commit")).thenReturn(Optional.of(artifact("commit",ArtifactType.COMMIT_PROPOSAL)));
        when(artifacts.find("tenant", "test")).thenReturn(Optional.of(artifact("test",ArtifactType.TEST_REPORT)));
        when(collaboration.review("owner","review")).thenReturn(Optional.of(new MultiAgentCollaborationApplicationApi.ReviewView("review","parent","child","reviewer",List.of("patch","commit","test"),AgentReviewDecision.APPROVED,"ok",NOW,NOW)));
        when(reconciliations.resolve(any())).thenReturn(view(ProjectReconciliationStepState.RESOLVED,3));
        when(executions.get(any())).thenReturn(execution(ProjectPlanExecutionState.BLOCKED,5));
        var service=new ProjectReconciliationIntegrationService(reconciliations,workspaces,executions,artifacts,collaboration,()->"00000000-0000-4000-8000-000000000099",mergeApi());
        service.resolveAndResume(new ProjectReconciliationIntegrationApi.ResolveCommand("tenant","owner",PROJECT,ID,2,"patch","commit","test","review",MERGE,ACTUAL,RESULT));
        verify(reconciliations).resolve(any(ProjectReconciliationApplicationApi.ResolveCommand.class)); verify(executions).resumeAfterReconciliation(any(ProjectPlanExecutionApplicationApi.ReconciliationResumeCommand.class));
    }
    private static ProjectReconciliationIntegrationService service(ProjectReconciliationApplicationApi r,WorkspaceApplicationApi w,ProjectPlanExecutionApplicationApi e){return new ProjectReconciliationIntegrationService(r,w,e,mock(ArtifactApplicationApi.class),mock(MultiAgentCollaborationApplicationApi.class),()->"00000000-0000-4000-8000-000000000099",mergeApi());}
    private static ProjectReconciliationApplicationApi.ReconciliationStepView view(ProjectReconciliationStepState state,long revision){return new ProjectReconciliationApplicationApi.ReconciliationStepView(ID,"tenant","owner",PROJECT,DIRECTORY,PLAN,STEP,EXECUTION,EXECUTION,MERGE,BASE,ACTUAL,id(),id(),id(),id(),state,state==ProjectReconciliationStepState.WAITING_RECONCILIATION?null:"00000000-0000-4000-8000-000000000010",state==ProjectReconciliationStepState.WAITING_RECONCILIATION?null:"00000000-0000-4000-8000-000000000011",state==ProjectReconciliationStepState.WAITING_RECONCILIATION?null:ACTUAL,null,null,revision,NOW,NOW,state==ProjectReconciliationStepState.RESOLVED?NOW:null);}
    private static WorkspaceApplicationApi.WorkspaceView workspace(){return new WorkspaceApplicationApi.WorkspaceView("00000000-0000-4000-8000-000000000010",PROJECT,DIRECTORY,"task",SOURCE,null,"reconciliation:"+ID,com.spaceagent.platform.project.domain.WorkspaceMode.MANAGED_GIT,"key",ACTUAL,"branch",null,ACTUAL,true,com.spaceagent.platform.project.domain.WorkspaceState.READY,null,1,NOW,NOW);}
    private static ProjectPlanExecutionApplicationApi.ExecutionView execution(ProjectPlanExecutionState state,long revision){return new ProjectPlanExecutionApplicationApi.ExecutionView(EXECUTION,"tenant","owner",PROJECT,DIRECTORY,"conversation",SOURCE,"task",PLAN,"agent","00000000-0000-4000-8000-000000000012","00000000-0000-4000-8000-000000000013","main",state,state==ProjectPlanExecutionState.BLOCKED?"PROJECT_RECONCILIATION_REQUIRED":null,0,revision,NOW,NOW,NOW,null,List.of());}
    private static ArtifactApplicationApi.ArtifactView artifact(String id,ArtifactType type){return new ArtifactApplicationApi.ArtifactView(id,PROJECT,"task","child","workspace",type,id,null,"sha256:"+"d".repeat(64),id,"{}",NOW);}
    private static com.spaceagent.platform.project.api.SourceMergeApplicationApi mergeApi(){var api=mock(com.spaceagent.platform.project.api.SourceMergeApplicationApi.class);var view=mock(com.spaceagent.platform.project.api.SourceMergeApplicationApi.SourceMergeView.class);when(view.targetRef()).thenReturn("refs/heads/feature/selected");when(api.get(any())).thenReturn(view);return api;}
    private static String id(){return "00000000-0000-4000-8000-000000000014";}
}
