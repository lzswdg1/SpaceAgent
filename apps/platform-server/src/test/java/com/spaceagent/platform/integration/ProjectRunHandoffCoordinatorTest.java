package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.integration.application.ProjectRunHandoffCoordinator;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.project.api.TaskExecutionApplicationApi;
import com.spaceagent.platform.project.api.TaskExecutionReferenceView;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRecoveryApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffState;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectRunHandoffCoordinatorTest {
    @Test
    void transfersBlockedJobIntoAnotherConversationAndAgentVersion() {
        Instant now = Instant.parse("2026-09-06T10:00:00Z");
        var jobs = mock(ProjectCodingJobApplicationApi.class);
        var handoffs = mock(ProjectRunHandoffApplicationApi.class);
        var recovery = mock(ProjectRecoveryApplicationApi.class);
        var runtime = mock(RuntimeApplicationApi.class);
        var execution = mock(TaskExecutionApplicationApi.class);
        var workspaces = mock(WorkspaceApplicationApi.class);
        var conversations = mock(ConversationApplicationApi.class);
        var agents = mock(AgentApplicationApi.class);
        var currentConfigurations = mock(AgentCurrentConfigurationApplicationApi.class);
        var memory = mock(MemoryApplicationApi.class);
        var ids = new UuidGenerator();
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:project_handoff;DB_CLOSE_DELAY=-1", "sa", "");
        var coordinator = new ProjectRunHandoffCoordinator(
                jobs, handoffs, recovery, runtime, execution, workspaces, conversations,
                agents, currentConfigurations, memory, ids, new ObjectMapper(),
                new DataSourceTransactionManager(dataSource));

        String project = id(), directory = id(), sourceRepository = id(), rootTask = id();
        String task = id(), plan = id(), step = id(), sourceJobId = id(), sourceRunId = id();
        String workspace = id(), targetConversation = id(), targetAgent = id();
        String reviewerAgent = id();
        String targetJobId = id(), snapshotId = id();
        var sourceJob = job(sourceJobId, project, directory, sourceRepository, rootTask, task,
                plan, step, sourceRunId, workspace, ProjectCodingJobState.BLOCKED, now);
        when(jobs.get(any())).thenReturn(sourceJob);
        when(handoffs.findBySourceCodingJobId(sourceJobId)).thenReturn(Optional.empty());
        when(runtime.findRun(sourceRunId)).thenReturn(Optional.of(new AgentRunView(
                sourceRunId, "source-agent", id(), "tenant", "owner", id(), project,
                directory, workspace, task, plan, step, ExecutionCursor.initial(), 3,
                AgentRunState.IN_PROGRESS, null, now, now, null)));
        when(execution.resolve(any())).thenReturn(new TaskExecutionReferenceView(
                project, rootTask, task, plan, step, TaskPlanStatus.ACTIVE,
                PlanStepState.IN_PROGRESS, TaskState.IN_PROGRESS));
        when(workspaces.get(any())).thenReturn(new WorkspaceApplicationApi.WorkspaceView(
                workspace, project, directory, task, sourceRepository, null,
                "plan-step:" + step, WorkspaceMode.MANAGED_GIT, id(), "main", "branch",
                "managed:" + workspace, "a".repeat(40), true, WorkspaceState.READY,
                null, 1, now, now));
        when(conversations.find(targetConversation)).thenReturn(Optional.of(new ConversationView(
                targetConversation, project, directory, null, rootTask, "tenant", "owner",
                targetAgent, "Continuation", ConversationStatus.ACTIVE, now, now)));
        when(agents.findById(targetAgent)).thenReturn(Optional.of(agent(targetAgent, now)));
        when(agents.findById(reviewerAgent)).thenReturn(Optional.of(agent(reviewerAgent, now)));
        var current = mock(AgentCurrentConfigurationApplicationApi.ConfigurationView.class);
        when(current.modelPoolId()).thenReturn("pool");
        when(currentConfigurations.requireCurrent(any(), any(), any())).thenReturn(current);
        var snapshot = new ProjectRecoveryApplicationApi.CodingRecoveryPackageView(
                snapshotId, "sha256:" + "a".repeat(64), now, null);
        when(recovery.capture(any())).thenReturn(snapshot);
        var targetJob = job(targetJobId, project, directory, sourceRepository, rootTask, task,
                plan, step, null, null, ProjectCodingJobState.PENDING, now);
        when(jobs.enqueue(any())).thenReturn(targetJob);
        when(handoffs.create(any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0, ProjectRunHandoffApplicationApi.CreateCommand.class);
            return handoff(command, now);
        });

        var result = coordinator.create(new ProjectRunHandoffCoordinator.CreateCommand(
                "tenant", "owner", project, plan, step, sourceJobId, targetConversation,
                targetAgent, reviewerAgent, "handoff-request-001"));

        assertThat(result.targetCodingJob().id()).isEqualTo(targetJobId);
        assertThat(result.handoff().recoverySnapshotId()).isEqualTo(snapshotId);
        assertThat(result.handoff().targetConversationId()).isEqualTo(targetConversation);
        verify(jobs).handoff(any());
        verify(runtime).markRunHandedOff(sourceRunId, result.handoff().id());
        verify(memory).saveProjectSnapshot(any());
    }

    private static ProjectCodingJobApplicationApi.JobView job(
            String id, String project, String directory, String source, String rootTask,
            String task, String plan, String step, String run, String workspace,
            ProjectCodingJobState state, Instant now) {
        return new ProjectCodingJobApplicationApi.JobView(
                id, "tenant", "owner", project, directory, id(), source, rootTask, task,
                plan, step, "source-agent", id(), id(), "main", state, workspace, run,
                null, 1, 0, null, null, null, null, null, null, "SAFE_BLOCKER", 1, 2,
                now, now, now, state == ProjectCodingJobState.BLOCKED ? now : null);
    }

    private static AgentDefinitionView agent(String id, Instant now) {
        return new AgentDefinitionView(id, "owner", "tenant", "Agent", null,
                AgentDefinitionStatus.ACTIVE, "prompt", null, "provider", "model",
                0, 4096, 10, "standard", true, false, false,
                List.of(), List.of(), List.of(), 1, now, now, null);
    }

    private static ProjectRunHandoffApplicationApi.HandoffView handoff(
            ProjectRunHandoffApplicationApi.CreateCommand command, Instant now) {
        return new ProjectRunHandoffApplicationApi.HandoffView(
                command.id(), command.tenantId(), command.ownerId(), command.projectId(),
                command.projectDirectoryId(), command.sourceRepositoryId(), command.rootTaskId(),
                command.taskId(), command.taskPlanId(), command.planStepId(), command.baseRef(),
                command.sourceCodingJobId(), command.sourceAgentRunId(), command.workspaceId(),
                command.recoverySnapshotId(), command.recoverySnapshotHash(),
                command.targetConversationId(), command.targetAgentId(), command.reviewerAgentId(),
                command.targetCodingJobId(), null,
                ProjectRunHandoffState.PENDING, null, null, 0, 1, now, now, null);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
