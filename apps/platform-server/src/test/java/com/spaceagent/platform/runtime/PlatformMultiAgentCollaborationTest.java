package com.spaceagent.platform.runtime;

import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.artifact.application.ArtifactApplicationService;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.artifact.infrastructure.memory.InMemoryArtifactRepository;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.AcceptHandoffCommand;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.CodingRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.CompleteHandoffCommand;
import com.spaceagent.platform.runtime.api.HandoffView;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.application.MultiAgentCollaborationApplicationService;
import com.spaceagent.platform.runtime.domain.AgentDelegationState;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.HandoffSnapshot;
import com.spaceagent.platform.runtime.domain.HandoffState;
import com.spaceagent.platform.runtime.domain.HandoffTestStatus;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryAgentDelegationRepository;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryAgentReviewRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformMultiAgentCollaborationTest {

    @Test
    void delegationGetsIsolatedWorkspaceHandoffAndEvidenceReview() {
        Instant now = Instant.parse("2026-08-23T12:00:00Z");
        RuntimeApplicationApi runtime = mock(RuntimeApplicationApi.class);
        AgentRunView parent = run("parent", "supervisor", "sv", AgentRunState.IN_PROGRESS, now);
        AgentRunView child = run("child", "coder", "cv", AgentRunState.COMPLETED, now);
        when(runtime.findRun("parent")).thenReturn(Optional.of(parent));
        when(runtime.findRun("child")).thenReturn(Optional.of(child));
        when(runtime.createHandoff(any())).thenReturn(new HandoffView(
                "handoff",
                "parent",
                null,
                HandoffState.PENDING,
                new HandoffSnapshot(
                        "goal", "state", List.of(), List.of(), List.of(), List.of(),
                        HandoffTestStatus.NOT_RUN, List.of(), List.of()),
                now,
                null));

        WorkspaceApplicationApi workspaces = mock(WorkspaceApplicationApi.class);
        when(workspaces.provision(any())).thenReturn(new WorkspaceApplicationApi.WorkspaceView(
                "workspace-child", "project", "task", "source", null,
                "delegation:00000000-0000-4000-8000-000000000001",
                WorkspaceMode.MANAGED_GIT, "key", "main", "branch", "managed:child",
                "a".repeat(40), true, WorkspaceState.READY, null, 1, now, now));
        CodingRuntimeApplicationApi coding = mock(CodingRuntimeApplicationApi.class);
        when(coding.start(any())).thenReturn(new CodingRuntimeApplicationApi.CodingRunView(
                "child", "workspace-child", "IN_PROGRESS", List.of()));
        AgentApplicationApi agents = mock(AgentApplicationApi.class);
        when(agents.findById("coder")).thenReturn(Optional.of(agent("coder", now)));
        when(agents.findById("reviewer")).thenReturn(Optional.of(agent("reviewer", now)));

        AtomicInteger sequence = new AtomicInteger();
        var ids = (com.spaceagent.shared.id.IdGenerator) () ->
                "00000000-0000-4000-8000-" + String.format("%012d", sequence.incrementAndGet());
        var artifactApi = new ArtifactApplicationService(
                new InMemoryArtifactRepository(), ids, () -> now);
        var delegationRepository = new InMemoryAgentDelegationRepository();
        var reviewRepository = new InMemoryAgentReviewRepository();
        var service = new MultiAgentCollaborationApplicationService(
                runtime,
                coding,
                workspaces,
                artifactApi,
                agents,
                currentConfigurations(),
                delegationRepository,
                reviewRepository,
                ids,
                () -> now);

        var delegation = service.delegate(new MultiAgentCollaborationApplicationApi.DelegateCommand(
                "user", "parent", "coder", "source", "main"));
        assertThat(delegation.workspaceId()).isEqualTo("workspace-child");
        assertThat(delegation.childRunId()).isEqualTo("child");
        ArgumentCaptor<WorkspaceApplicationApi.ProvisionCommand> provision =
                ArgumentCaptor.forClass(WorkspaceApplicationApi.ProvisionCommand.class);
        verify(workspaces).provision(provision.capture());
        assertThat(provision.getValue().isolationKey()).isEqualTo("delegation:" + delegation.id());
        verify(runtime).acceptHandoff(new AcceptHandoffCommand("handoff", "child"));

        var patch = artifactApi.create(new com.spaceagent.platform.artifact.api.ArtifactApplicationApi.CreateCommand(
                "tenant", "project", "task", "child", "workspace-child", ArtifactType.PATCH,
                "workspace.patch", "ref", "sha256:patch", "patch", "{}"));
        var commit = artifactApi.create(new com.spaceagent.platform.artifact.api.ArtifactApplicationApi.CreateCommand(
                "tenant", "project", "task", "child", "workspace-child", ArtifactType.COMMIT_PROPOSAL,
                "commit", "ref", "sha256:commit", "commit", "{}"));
        var review = service.requestReview(new MultiAgentCollaborationApplicationApi.ReviewCommand(
                "user", "parent", "child", "reviewer", List.of(patch.id(), commit.id())));
        var approved = service.decide(new MultiAgentCollaborationApplicationApi.DecideReviewCommand(
                "user", review.id(), AgentReviewDecision.APPROVED, "patch and tests accepted"));

        assertThat(approved.decision()).isEqualTo(AgentReviewDecision.APPROVED);
        assertThat(service.delegations("user", "parent")).singleElement().satisfies(item ->
                assertThat(item.state()).isEqualTo(AgentDelegationState.COMPLETED));
        verify(runtime).completeHandoff(new CompleteHandoffCommand("handoff"));
    }

    @Test
    void reviewRejectsUndelegatedOrIncompleteChildRuns() {
        Instant now = Instant.parse("2026-08-23T12:00:00Z");
        RuntimeApplicationApi runtime = mock(RuntimeApplicationApi.class);
        when(runtime.findRun("parent")).thenReturn(Optional.of(
                run("parent", "supervisor", "sv", AgentRunState.IN_PROGRESS, now)));
        when(runtime.findRun("other")).thenReturn(Optional.of(
                run("other", "coder", "cv", AgentRunState.IN_PROGRESS, now)));
        var service = new MultiAgentCollaborationApplicationService(
                runtime,
                mock(CodingRuntimeApplicationApi.class),
                mock(WorkspaceApplicationApi.class),
                mock(com.spaceagent.platform.artifact.api.ArtifactApplicationApi.class),
                mock(AgentApplicationApi.class),
                currentConfigurations(),
                new InMemoryAgentDelegationRepository(),
                new InMemoryAgentReviewRepository(),
                () -> "00000000-0000-4000-8000-000000000001",
                () -> now);

        assertThatThrownBy(() -> service.requestReview(
                new MultiAgentCollaborationApplicationApi.ReviewCommand(
                        "user", "parent", "other", "rv", List.of("artifact"))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getMessage()).contains("not delegated"));
    }

    @Test
    void failedChildStartupArchivesTheProvisionedWorkspace() {
        Instant now = Instant.parse("2026-08-23T12:00:00Z");
        RuntimeApplicationApi runtime = mock(RuntimeApplicationApi.class);
        when(runtime.findRun("parent")).thenReturn(Optional.of(
                run("parent", "supervisor", "sv", AgentRunState.IN_PROGRESS, now)));
        WorkspaceApplicationApi workspaces = mock(WorkspaceApplicationApi.class);
        when(workspaces.provision(any())).thenReturn(new WorkspaceApplicationApi.WorkspaceView(
                "workspace-child", "project", "task", "source", null,
                "delegation:00000000-0000-4000-8000-000000000001",
                WorkspaceMode.MANAGED_GIT, "key", "main", "branch", "managed:child",
                "a".repeat(40), true, WorkspaceState.READY, null, 1, now, now));
        CodingRuntimeApplicationApi coding = mock(CodingRuntimeApplicationApi.class);
        when(coding.start(any())).thenThrow(new IllegalStateException("start failed"));
        AgentApplicationApi agents = mock(AgentApplicationApi.class);
        when(agents.findById("coder")).thenReturn(Optional.of(agent("coder", now)));
        var service = new MultiAgentCollaborationApplicationService(
                runtime,
                coding,
                workspaces,
                mock(com.spaceagent.platform.artifact.api.ArtifactApplicationApi.class),
                agents,
                currentConfigurations(),
                new InMemoryAgentDelegationRepository(),
                new InMemoryAgentReviewRepository(),
                () -> "00000000-0000-4000-8000-000000000001",
                () -> now);

        assertThatThrownBy(() -> service.delegate(
                new MultiAgentCollaborationApplicationApi.DelegateCommand(
                        "user", "parent", "coder", "source", "main")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("start failed");
        verify(workspaces).archive(new WorkspaceApplicationApi.ArchiveCommand(
                "tenant", "user", "project", "workspace-child"));
    }

    @Test
    void activeCodingRunCanReceiveDistinctStandaloneReviewWithoutDelegation() {
        Instant now = Instant.parse("2026-08-23T12:00:00Z");
        RuntimeApplicationApi runtime = mock(RuntimeApplicationApi.class);
        when(runtime.findRun("coding")).thenReturn(Optional.of(
                run("coding", "coder", "cv", AgentRunState.IN_PROGRESS, now)));
        AgentApplicationApi agents = mock(AgentApplicationApi.class);
        when(agents.findById("reviewer")).thenReturn(Optional.of(agent("reviewer", now)));
        AtomicInteger sequence = new AtomicInteger();
        var ids = (com.spaceagent.shared.id.IdGenerator) () ->
                "00000000-0000-4000-8000-" + String.format("%012d", sequence.incrementAndGet());
        var artifactApi = new ArtifactApplicationService(
                new InMemoryArtifactRepository(), ids, () -> now);
        var patch = artifactApi.create(new com.spaceagent.platform.artifact.api.ArtifactApplicationApi.CreateCommand(
                "tenant", "project", "task", "coding", "workspace", ArtifactType.PATCH,
                "patch", "ref", "sha256:patch", "patch", "{}"));
        var commit = artifactApi.create(new com.spaceagent.platform.artifact.api.ArtifactApplicationApi.CreateCommand(
                "tenant", "project", "task", "coding", "workspace", ArtifactType.COMMIT_PROPOSAL,
                "commit", "ref", "sha256:patch", "commit", "{}"));
        var test = artifactApi.create(new com.spaceagent.platform.artifact.api.ArtifactApplicationApi.CreateCommand(
                "tenant", "project", "task", "coding", "workspace", ArtifactType.TEST_REPORT,
                "test", "ref", "sha256:test", "pass", "{\"exitCode\":0}"));
        var service = new MultiAgentCollaborationApplicationService(
                runtime, mock(CodingRuntimeApplicationApi.class), mock(WorkspaceApplicationApi.class),
                artifactApi, agents, currentConfigurations(), new InMemoryAgentDelegationRepository(),
                new InMemoryAgentReviewRepository(), ids, () -> now);

        var review = service.requestExecutionReview(
                new MultiAgentCollaborationApplicationApi.ExecutionReviewCommand(
                        "user", "coding", "reviewer", List.of(patch.id(), commit.id(), test.id())));
        var approved = service.decideExecutionReview(
                new MultiAgentCollaborationApplicationApi.DecideReviewCommand(
                        "user", review.id(), AgentReviewDecision.APPROVED, "verified"));

        assertThat(approved.parentRunId()).isEqualTo("coding");
        assertThat(approved.childRunId()).isEqualTo("coding");
        assertThat(approved.decision()).isEqualTo(AgentReviewDecision.APPROVED);
    }

    private static AgentRunView run(
            String id,
            String agent,
            String version,
            AgentRunState state,
            Instant now) {
        return new AgentRunView(
                id, agent, version, "tenant", "user", "conversation", "project", "task",
                "plan", "step", new ExecutionCursor("execute", null, null, 0), 1,
                state, null, now, now, state == AgentRunState.COMPLETED ? now : null);
    }

    private static AgentDefinitionView agent(String id, Instant now) {
        return new AgentDefinitionView(
                id, "user", "tenant", id, null, AgentDefinitionStatus.ACTIVE, "", null,
                null, null, .2, 2048, 10, "private", true, false, false, List.of(), List.of(),
                List.of(), 1, now, now, null);
    }

    private static AgentCurrentConfigurationApplicationApi currentConfigurations() {
        var configurations = mock(AgentCurrentConfigurationApplicationApi.class);
        when(configurations.requireCurrent(any(), any(), any())).thenReturn(
                mock(AgentCurrentConfigurationApplicationApi.ConfigurationView.class));
        return configurations;
    }
}
