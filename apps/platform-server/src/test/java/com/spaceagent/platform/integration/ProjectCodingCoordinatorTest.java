package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.runtime.api.AgentExecutionConfigurationView;
import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.integration.api.ReviewedSourceMergeApplicationApi;
import com.spaceagent.platform.integration.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.platform.integration.application.ProjectCodingCoordinator;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.project.api.PlanStepView;
import com.spaceagent.platform.project.api.ProjectBlueprintApplicationApi;
import com.spaceagent.platform.project.api.ProjectDirectoryApplicationApi;
import com.spaceagent.platform.project.api.SourceMergeApplicationApi;
import com.spaceagent.platform.project.api.SourceRepositoryApplicationApi;
import com.spaceagent.platform.project.api.SourceRepositoryView;
import com.spaceagent.platform.project.api.TaskExecutionReferenceView;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskExecutionApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanView;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.SourceMergeState;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import com.spaceagent.platform.project.domain.ProjectDirectoryState;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.AgentRunConfigurationSnapshotApplicationApi;
import com.spaceagent.platform.runtime.api.CodingRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.CreateCheckpointCommand;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRecoveryApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.platform.runtime.api.RunStepView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolExecutionApplicationApi;
import com.spaceagent.platform.runtime.application.ProjectCodingJobApplicationService;
import com.spaceagent.platform.runtime.application.ProjectPlanExecutionApplicationService;
import com.spaceagent.platform.runtime.application.ProjectPlanMergeBarrierApplicationService;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.domain.RunStepState;
import com.spaceagent.platform.runtime.domain.RuntimeOperationalTelemetry;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectCodingJobRepository;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectPlanExecutionRepository;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectCodingCoordinatorTest {
    @Test
    void deterministicFailureMarksExecutionFailedInsteadOfEffectBlocked() {
        Instant now = Instant.parse("2026-09-08T01:00:00Z");
        var jobs = mock(ProjectCodingJobApplicationApi.class);
        var planExecutions = mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.class);
        var directories = mock(ProjectDirectoryApplicationApi.class);
        var sources = mock(SourceRepositoryApplicationApi.class);
        var taskExecution = mock(TaskExecutionApplicationApi.class);
        var tasks = mock(TaskApplicationApi.class);
        var plans = mock(TaskPlanApplicationApi.class);
        var blueprints = mock(ProjectBlueprintApplicationApi.class);
        var conversations = mock(ConversationApplicationApi.class);
        var agents = mock(AgentApplicationApi.class);
        var workspaces = mock(WorkspaceApplicationApi.class);
        var coding = mock(CodingRuntimeApplicationApi.class);
        var runtime = mock(RuntimeApplicationApi.class);
        var coordination = mock(
                com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi.class);
        var tools = mock(RuntimeToolExecutionApplicationApi.class);
        var catalog = mock(RuntimeCapabilityCatalogApplicationApi.class);
        var pools = mock(ModelPoolApplicationApi.class);
        var inference = mock(InferenceExecutionApi.class);
        var modelCalls = mock(
                com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi.class);
        var toolLedger = mock(
                com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi.class);
        var governance = mock(
                com.spaceagent.platform.governance.api.GovernanceApplicationApi.class);
        var artifacts = mock(ArtifactApplicationApi.class);
        var collaboration = mock(MultiAgentCollaborationApplicationApi.class);
        var merges = mock(ReviewedSourceMergeApplicationApi.class);
        var handoffs = mock(ProjectRunHandoffApplicationApi.class);
        var recovery = mock(ProjectRecoveryApplicationApi.class);
        var memory = mock(MemoryApplicationApi.class);
        var coordinator = new ProjectCodingCoordinator(
                jobs, planExecutions, directories, sources, taskExecution, tasks, plans,
                blueprints, conversations, agents, workspaces, coding, runtime,
                coordination, tools, catalog, pools, inference, modelCalls, toolLedger,
                governance, artifacts, collaboration, merges, handoffs, recovery, memory,
                RuntimeOperationalTelemetry.noop(), new ObjectMapper(),
                new DataSourceTransactionManager(new DriverManagerDataSource(
                        "jdbc:h2:mem:project_plan_failure;DB_CLOSE_DELAY=-1", "sa", "")));
        var job = mock(ProjectCodingJobApplicationApi.JobView.class);
        String jobId = id(), projectId = id(), taskPlanId = id(), planStepId = id();
        String taskId = id(), executionId = id(), configurationHash = id();
        when(job.id()).thenReturn(jobId);
        when(job.tenantId()).thenReturn("tenant");
        when(job.ownerId()).thenReturn("user");
        when(job.projectId()).thenReturn(projectId);
        when(job.taskPlanId()).thenReturn(taskPlanId);
        when(job.planStepId()).thenReturn(planStepId);
        when(job.taskId()).thenReturn(taskId);
        when(job.executionId()).thenReturn(executionId);
        when(job.primaryConfigurationHash()).thenReturn(configurationHash);
        when(job.state()).thenReturn(ProjectCodingJobState.RUNNING);
        String claimToken = id();
        var claim = new ProjectCodingJobApplicationApi.ClaimView(
                job, claimToken, 1, now.plusSeconds(60), null, null);
        when(jobs.claim(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.of(claim));
        when(jobs.get(any())).thenReturn(job);
        when(directories.get(any())).thenThrow(new BusinessException(
                "invalid directory", HttpStatus.CONFLICT,
                "PROJECT_CODING_DIRECTORY_INVALID"));
        when(taskExecution.resolve(any())).thenReturn(new TaskExecutionReferenceView(
                projectId, id(), taskId, taskPlanId, planStepId,
                TaskPlanStatus.ACTIVE, PlanStepState.PENDING, TaskState.PENDING));
        when(planExecutions.fail(any(), any())).thenReturn(mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi
                        .ExecutionView.class));
        when(jobs.fail(any())).thenReturn(job);

        assertThat(coordinator.runOnce("worker", 300, 3, 5, 1)).isTrue();

        verify(planExecutions).fail(any(),
                org.mockito.ArgumentMatchers.eq("PROJECT_CODING_DIRECTORY_INVALID"));
        verify(planExecutions, org.mockito.Mockito.never()).block(any(), any());
    }

    @Test
    void approvedPlanDispatchActivatesAndIdempotentlyMaterializesOnlyFirstReadyStep() {
        Instant now = Instant.parse("2026-09-07T06:00:00Z");
        ObjectMapper json = new ObjectMapper();
        var jobRepository = new InMemoryProjectCodingJobRepository();
        var jobs = new ProjectCodingJobApplicationService(
                jobRepository, new UuidGenerator(), () -> now, json);
        var planExecutions = new ProjectPlanExecutionApplicationService(
                new InMemoryProjectPlanExecutionRepository(), jobRepository, () -> now);
        var directories = mock(ProjectDirectoryApplicationApi.class);
        var sources = mock(SourceRepositoryApplicationApi.class);
        var execution = mock(TaskExecutionApplicationApi.class);
        var tasks = mock(TaskApplicationApi.class);
        var plans = mock(TaskPlanApplicationApi.class);
        var blueprints = mock(ProjectBlueprintApplicationApi.class);
        var conversations = mock(ConversationApplicationApi.class);
        var agents = mock(AgentApplicationApi.class);
        var workspaces = mock(WorkspaceApplicationApi.class);
        var coding = mock(CodingRuntimeApplicationApi.class);
        var runtime = mock(RuntimeApplicationApi.class);
        var coordination = mock(com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi.class);
        var tools = mock(RuntimeToolExecutionApplicationApi.class);
        var catalog = mock(RuntimeCapabilityCatalogApplicationApi.class);
        var pools = mock(ModelPoolApplicationApi.class);
        var inference = mock(InferenceExecutionApi.class);
        var modelCalls = mock(com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi.class);
        var toolLedger = mock(com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi.class);
        var governance = mock(com.spaceagent.platform.governance.api.GovernanceApplicationApi.class);
        var artifacts = mock(ArtifactApplicationApi.class);
        var collaboration = mock(MultiAgentCollaborationApplicationApi.class);
        var merges = mock(ReviewedSourceMergeApplicationApi.class);
        var handoffs = mock(ProjectRunHandoffApplicationApi.class);
        var recovery = mock(ProjectRecoveryApplicationApi.class);
        var memory = mock(MemoryApplicationApi.class);
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:project_plan_dispatch;DB_CLOSE_DELAY=-1", "sa", "");
        var coordinator = new ProjectCodingCoordinator(
                jobs, planExecutions, directories, sources, execution, tasks, plans, blueprints,
                conversations, agents, workspaces, coding, runtime, coordination, tools,
                catalog, pools, inference, modelCalls, toolLedger, governance, artifacts, collaboration, merges, handoffs,
                recovery, memory, RuntimeOperationalTelemetry.noop(), json,
                new DataSourceTransactionManager(ds));

        String project = id(), directory = id(), conversation = id(), source = id();
        String root = id(), firstTask = id(), secondTask = id(), planId = id();
        String firstStep = id(), secondStep = id(), agent = id(), configurationHash = id();
        String reviewerAgent = id(), reviewerVersion = id();
        var first = new PlanStepView(
                firstStep, "inspect", 0, firstTask, List.of(), null, agent,
                "Inspect", List.of("inspection complete"), false,
                PlanStepState.PENDING, now, now);
        var second = new PlanStepView(
                secondStep, "implement", 1, secondTask, List.of(firstStep), null, agent,
                "Implement", List.of("tests pass"), false,
                PlanStepState.PENDING, now, now);
        var approved = new TaskPlanView(
                planId, project, root, 1, TaskPlanStatus.APPROVED, configurationHash,
                "user", "user", now, List.of(first, second), now, now);
        var active = new TaskPlanView(
                planId, project, root, 1, TaskPlanStatus.ACTIVE, configurationHash,
                "user", "user", now, List.of(first, second), now, now);
        when(plans.getPlan(any())).thenReturn(approved, active, active);
        when(plans.transition(any())).thenReturn(active);
        when(catalog.catalog()).thenReturn(
                new RuntimeCapabilityCatalogApplicationApi.CapabilityCatalogView(
                        List.of(), List.of(),
                        new RuntimeCapabilityCatalogApplicationApi.SandboxCapabilityView(
                                "OCI", "WORKSPACE", true, true)));
        when(directories.get(any())).thenReturn(
                new ProjectDirectoryApplicationApi.DirectoryView(
                        directory, "tenant", project, source, "repo", ".", false,
                        ProjectDirectoryState.ACTIVE, now, now));
        when(sources.get(any())).thenReturn(new SourceRepositoryView(
                source, project, null, null, null, "repo", "repo",
                "https://example.com/repo.git", null, "main",
                SourceRepositoryType.GIT, SourceRepositoryState.READY,
                SourceRepositoryVisibility.PUBLIC, "user", now, now));
        when(execution.resolve(any())).thenReturn(new TaskExecutionReferenceView(
                project, root, firstTask, planId, firstStep, TaskPlanStatus.ACTIVE,
                PlanStepState.PENDING, TaskState.PENDING));
        when(conversations.find(conversation)).thenReturn(Optional.of(new ConversationView(
                conversation, project, directory, null, root, "tenant", "user", agent,
                "Coding", ConversationStatus.ACTIVE, now, now)));
        AgentDefinitionView coder = activeAgent(agent, now);
        AgentDefinitionView reviewer = activeAgent(reviewerAgent, now);
        when(agents.findById(agent)).thenReturn(Optional.of(coder));
        when(agents.findById(reviewerAgent)).thenReturn(Optional.of(reviewer));

        var command = new ProjectPlanExecutionApplicationApi.DispatchCommand(
                "tenant", "user", project, root, planId, directory, conversation,
                source, agent, configurationHash, reviewerVersion, "main", reviewerAgent);
        var firstDispatch = coordinator.dispatch(command);
        var replay = coordinator.dispatch(command);

        var currentExecution = planExecutions.get(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.Query(
                        "tenant", "user", project, firstDispatch.executionId()));
        var pause = planExecutions.requestPause(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.PauseCommand(
                        "tenant", "user", project, planId, firstDispatch.executionId(),
                        currentExecution.revision(), "maintenance"));
        var pausedDispatch = coordinator.dispatch(command);

        assertThat(firstDispatch.taskPlanState()).isEqualTo("ACTIVE");
        assertThat(firstDispatch.materialized()).isTrue();
        assertThat(firstDispatch.executionId()).isEqualTo(firstDispatch.activeJob().executionId());
        assertThat(firstDispatch.activeJob().planStepId()).isEqualTo(firstStep);
        assertThat(replay.materialized()).isFalse();
        assertThat(replay.activeJob().id()).isEqualTo(firstDispatch.activeJob().id());
        assertThat(pause.state()).isEqualTo(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSING);
        assertThat(pausedDispatch.activeJob()).isNull();
        assertThat(pausedDispatch.materialized()).isFalse();
        var paused = planExecutions.acknowledgePause(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.TransitionCommand(
                        "tenant", "user", project, planId, firstDispatch.executionId()));
        var resumedBeforeStart = coordinator.resume(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ResumeCommand(
                        "tenant", "user", project, planId, firstDispatch.executionId(), paused.revision()));
        assertThat(resumedBeforeStart.state()).isEqualTo(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.RUNNING);
        assertThat(jobs.list(new ProjectCodingJobApplicationApi.ListQuery(
                "tenant", "user", project, planId, firstStep, 1, 100)).total())
                .isEqualTo(1);
        assertThat(jobs.list(new ProjectCodingJobApplicationApi.ListQuery(
                "tenant", "user", project, planId, secondStep, 1, 100)).total())
                .isZero();
        verify(plans).transition(any());
    }

    @Test
    void pauseBoundaryPersistsCheckpointBeforeAcknowledgement() {
        Instant now = Instant.parse("2026-09-07T07:00:00Z");
        ObjectMapper json = new ObjectMapper();
        var jobs = mock(ProjectCodingJobApplicationApi.class);
        var planExecutions = mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.class);
        var directories = mock(ProjectDirectoryApplicationApi.class);
        var sources = mock(SourceRepositoryApplicationApi.class);
        var execution = mock(TaskExecutionApplicationApi.class);
        var tasks = mock(TaskApplicationApi.class);
        var plans = mock(TaskPlanApplicationApi.class);
        var blueprints = mock(ProjectBlueprintApplicationApi.class);
        var conversations = mock(ConversationApplicationApi.class);
        var agents = mock(AgentApplicationApi.class);
        var workspaces = mock(WorkspaceApplicationApi.class);
        var coding = mock(CodingRuntimeApplicationApi.class);
        var runtime = mock(RuntimeApplicationApi.class);
        var coordination = mock(com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi.class);
        var tools = mock(RuntimeToolExecutionApplicationApi.class);
        var catalog = mock(RuntimeCapabilityCatalogApplicationApi.class);
        var pools = mock(ModelPoolApplicationApi.class);
        var inference = mock(InferenceExecutionApi.class);
        var modelCalls = mock(com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi.class);
        var toolLedger = mock(com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi.class);
        var governance = mock(com.spaceagent.platform.governance.api.GovernanceApplicationApi.class);
        var artifacts = mock(ArtifactApplicationApi.class);
        var collaboration = mock(MultiAgentCollaborationApplicationApi.class);
        var merges = mock(ReviewedSourceMergeApplicationApi.class);
        var handoffs = mock(ProjectRunHandoffApplicationApi.class);
        var recovery = mock(ProjectRecoveryApplicationApi.class);
        var memory = mock(MemoryApplicationApi.class);
        var coordinator = new ProjectCodingCoordinator(
                jobs, planExecutions, directories, sources, execution, tasks, plans, blueprints,
                conversations, agents, workspaces, coding, runtime, coordination, tools, catalog,
                pools, inference, modelCalls, toolLedger, governance, artifacts, collaboration, merges, handoffs, recovery, memory,
                RuntimeOperationalTelemetry.noop(), json,
                new DataSourceTransactionManager(new DriverManagerDataSource(
                        "jdbc:h2:mem:project_plan_pause_boundary;DB_CLOSE_DELAY=-1", "sa", "")));

        String executionId = id();
        var executionView = mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView.class);
        when(executionView.state()).thenReturn(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSING);
        when(planExecutions.get(any())).thenReturn(executionView);
        when(planExecutions.acknowledgePause(any())).thenReturn(executionView);
        var job = mock(ProjectCodingJobApplicationApi.JobView.class);
        when(job.id()).thenReturn(id());
        when(job.tenantId()).thenReturn("tenant");
        when(job.ownerId()).thenReturn("user");
        when(job.projectId()).thenReturn(id());
        when(job.taskPlanId()).thenReturn(id());
        when(job.planStepId()).thenReturn(id());
        when(job.executionId()).thenReturn(executionId);
        when(job.codingRunId()).thenReturn(id());
        when(job.revision()).thenReturn(7L);
        when(jobs.releaseForPause(any())).thenReturn(job);

        assertThat(coordinator.pauseIfRequested(job, new ProjectCodingJobApplicationApi.ClaimCommand(
                job.id(), "worker", id(), 3L))).isTrue();
        ArgumentCaptor<CreateCheckpointCommand> checkpoint =
                ArgumentCaptor.forClass(CreateCheckpointCommand.class);
        verify(runtime).createCheckpoint(checkpoint.capture());
        assertThat(checkpoint.getValue().stateSnapshot())
                .contains("project-plan-paused", executionId, job.id(), job.taskPlanId(), job.planStepId());
        verify(jobs).releaseForPause(any());
        verify(planExecutions).acknowledgePause(any());

        String runId = job.codingRunId();
        String jobProjectId = job.projectId();
        String jobTaskPlanId = job.taskPlanId();
        when(executionView.id()).thenReturn(executionId);
        when(executionView.tenantId()).thenReturn("tenant");
        when(executionView.ownerId()).thenReturn("user");
        when(executionView.projectId()).thenReturn(jobProjectId);
        when(executionView.rootTaskId()).thenReturn(id());
        when(executionView.taskPlanId()).thenReturn(jobTaskPlanId);
        when(executionView.state()).thenReturn(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.CANCELLING);
        when(modelCalls.findByRunId(runId)).thenReturn(List.of());
        when(toolLedger.findByRunId(runId)).thenReturn(List.of());
        var run = mock(com.spaceagent.platform.runtime.api.AgentRunView.class);
        when(run.id()).thenReturn(runId);
        when(run.state()).thenReturn(AgentRunState.IN_PROGRESS);
        when(runtime.findRun(runId)).thenReturn(Optional.of(run));
        var runLease = new com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi.LeaseView(
                runId, id(), "project-plan-cancel:worker", 5, now.plusSeconds(60),
                1, now, now, null);
        when(coordination.acquireLease(any())).thenReturn(
                new com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi.LeaseClaimView(
                        com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaimType.ACQUIRED,
                        runLease, null));
        when(runtime.cancelFenced(any())).thenReturn(run);
        when(jobs.fail(any())).thenReturn(job);
        when(planExecutions.acknowledgeCancel(any())).thenReturn(executionView);
        var activePlan = mock(TaskPlanView.class);
        when(activePlan.status()).thenReturn(TaskPlanStatus.ACTIVE);
        when(plans.getPlan(any())).thenReturn(activePlan);
        when(plans.transition(any())).thenReturn(activePlan);
        var cancelClaim = new ProjectCodingJobApplicationApi.ClaimCommand(
                job.id(), "worker", id(), 3L);

        assertThat(coordinator.cancelIfRequested(job, cancelClaim)).isTrue();
        verify(runtime).cancelFenced(any());
        verify(jobs).fail(any());
        verify(planExecutions).acknowledgeCancel(any());

        var pausingRecovery = mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView.class);
        when(pausingRecovery.state()).thenReturn(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSING);
        when(pausingRecovery.activeJobs()).thenReturn(List.of());
        when(pausingRecovery.id()).thenReturn(id());
        when(pausingRecovery.tenantId()).thenReturn("tenant");
        when(pausingRecovery.ownerId()).thenReturn("user");
        when(pausingRecovery.projectId()).thenReturn(id());
        when(pausingRecovery.taskPlanId()).thenReturn(id());
        var cancellingRecovery = mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView.class);
        when(cancellingRecovery.state()).thenReturn(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.CANCELLING);
        when(cancellingRecovery.activeJobs()).thenReturn(List.of());
        when(cancellingRecovery.id()).thenReturn(id());
        when(cancellingRecovery.tenantId()).thenReturn("tenant");
        when(cancellingRecovery.ownerId()).thenReturn("user");
        when(cancellingRecovery.projectId()).thenReturn(id());
        when(cancellingRecovery.rootTaskId()).thenReturn(id());
        when(cancellingRecovery.taskPlanId()).thenReturn(id());
        when(planExecutions.controlTransitions(10))
                .thenReturn(List.of(pausingRecovery, cancellingRecovery));

        assertThat(coordinator.recoverControlTransitions(10)).isEqualTo(2);
        verify(planExecutions, org.mockito.Mockito.times(2)).acknowledgePause(any());
        verify(planExecutions, org.mockito.Mockito.times(2)).acknowledgeCancel(any());

        when(planExecutions.begin(any())).thenReturn(pausingRecovery);
        com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView
                terminalDeferred = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                coordinator, "completeReviewedJob", job, cancelClaim, id());
        assertThat(terminalDeferred).isSameAs(pausingRecovery);
        verify(execution, org.mockito.Mockito.never()).transition(any());
        verify(jobs, org.mockito.Mockito.never()).complete(any(), any());
    }

    @Test
    void resumeRevalidatesOwnerBoundariesBeforeFencedTransition() {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        ObjectMapper json = new ObjectMapper();
        var jobs = mock(ProjectCodingJobApplicationApi.class);
        var planExecutions = mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.class);
        var directories = mock(ProjectDirectoryApplicationApi.class);
        var sources = mock(SourceRepositoryApplicationApi.class);
        var taskExecution = mock(TaskExecutionApplicationApi.class);
        var tasks = mock(TaskApplicationApi.class);
        var plans = mock(TaskPlanApplicationApi.class);
        var blueprints = mock(ProjectBlueprintApplicationApi.class);
        var conversations = mock(ConversationApplicationApi.class);
        var agents = mock(AgentApplicationApi.class);
        var workspaces = mock(WorkspaceApplicationApi.class);
        var coding = mock(CodingRuntimeApplicationApi.class);
        var runtime = mock(RuntimeApplicationApi.class);
        var coordination = mock(com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi.class);
        var tools = mock(RuntimeToolExecutionApplicationApi.class);
        var catalog = mock(RuntimeCapabilityCatalogApplicationApi.class);
        var pools = mock(ModelPoolApplicationApi.class);
        var inference = mock(InferenceExecutionApi.class);
        var modelCalls = mock(com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi.class);
        var toolLedger = mock(com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi.class);
        var governance = mock(com.spaceagent.platform.governance.api.GovernanceApplicationApi.class);
        var artifacts = mock(ArtifactApplicationApi.class);
        var collaboration = mock(MultiAgentCollaborationApplicationApi.class);
        var merges = mock(ReviewedSourceMergeApplicationApi.class);
        var handoffs = mock(ProjectRunHandoffApplicationApi.class);
        var recovery = mock(ProjectRecoveryApplicationApi.class);
        var memory = mock(MemoryApplicationApi.class);
        var coordinator = new ProjectCodingCoordinator(
                jobs, planExecutions, directories, sources, taskExecution, tasks, plans, blueprints,
                conversations, agents, workspaces, coding, runtime, coordination, tools, catalog, pools,
                inference, modelCalls, toolLedger, governance, artifacts, collaboration, merges,
                handoffs, recovery, memory, RuntimeOperationalTelemetry.noop(), json,
                new DataSourceTransactionManager(new DriverManagerDataSource(
                        "jdbc:h2:mem:project_plan_resume;DB_CLOSE_DELAY=-1", "sa", "")));

        String projectId = id(), directoryId = id(), conversationId = id(), sourceId = id();
        String rootTaskId = id(), taskId = id(), taskPlanId = id(), planStepId = id();
        String executionId = id(), jobId = id(), workspaceId = id(), runId = id();
        String agentId = id(), configurationHash = id();
        var activeJob = new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ActiveJobView(
                jobId, planStepId, ProjectCodingJobState.PENDING, workspaceId, runId, null, 4, now);
        var paused = mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView.class);
        when(paused.state()).thenReturn(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSED);
        when(paused.revision()).thenReturn(7L);
        when(paused.rootTaskId()).thenReturn(rootTaskId);
        when(paused.activeJobs()).thenReturn(List.of(activeJob));
        var resumed = mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView.class);
        when(resumed.state()).thenReturn(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.RUNNING);
        when(planExecutions.get(any())).thenReturn(paused);
        when(planExecutions.resume(any())).thenReturn(resumed);

        var step = new PlanStepView(
                planStepId, "resume", 0, taskId, List.of(), null, agentId,
                "continue", List.of("resume safely"), false,
                PlanStepState.IN_PROGRESS, now, now);
        when(plans.getPlan(any())).thenReturn(new TaskPlanView(
                taskPlanId, projectId, rootTaskId, 1, TaskPlanStatus.ACTIVE,
                configurationHash, "user", "user", now, List.of(step), now, now));
        var job = mock(ProjectCodingJobApplicationApi.JobView.class);
        when(job.id()).thenReturn(jobId);
        when(job.tenantId()).thenReturn("tenant");
        when(job.ownerId()).thenReturn("user");
        when(job.projectId()).thenReturn(projectId);
        when(job.projectDirectoryId()).thenReturn(directoryId);
        when(job.sourceRepositoryId()).thenReturn(sourceId);
        when(job.taskId()).thenReturn(taskId);
        when(job.taskPlanId()).thenReturn(taskPlanId);
        when(job.executionId()).thenReturn(executionId);
        when(job.planStepId()).thenReturn(planStepId);
        when(job.agentId()).thenReturn(agentId);
        when(job.primaryConfigurationHash()).thenReturn(configurationHash);
        when(job.workspaceId()).thenReturn(workspaceId);
        when(job.codingRunId()).thenReturn(runId);
        when(job.state()).thenReturn(ProjectCodingJobState.PENDING);
        when(jobs.get(any())).thenReturn(job);
        when(workspaces.get(any())).thenReturn(new WorkspaceApplicationApi.WorkspaceView(
                workspaceId, projectId, directoryId, taskId, sourceId, null,
                "plan-step:" + planStepId, WorkspaceMode.MANAGED_GIT, id(), "main", "branch",
                "managed", "a".repeat(40), true, WorkspaceState.READY, null, 1, now, now));
        AgentDefinitionView resumableAgent = activeAgent(agentId, now);
        when(agents.findById(agentId)).thenReturn(Optional.of(resumableAgent));
        var run = mock(com.spaceagent.platform.runtime.api.AgentRunView.class);
        when(run.id()).thenReturn(runId);
        when(run.tenantId()).thenReturn("tenant");
        when(run.ownerId()).thenReturn("user");
        when(run.projectId()).thenReturn(projectId);
        when(run.projectDirectoryId()).thenReturn(directoryId);
        when(run.workspaceId()).thenReturn(workspaceId);
        when(run.taskId()).thenReturn(taskId);
        when(run.taskPlanId()).thenReturn(taskPlanId);
        when(run.planStepId()).thenReturn(planStepId);
        when(run.agentId()).thenReturn(agentId);
        when(run.configurationSnapshotId()).thenReturn(runId);
        when(run.state()).thenReturn(AgentRunState.IN_PROGRESS);
        when(runtime.findRun(runId)).thenReturn(Optional.of(run));
        when(runtime.findLatestCheckpointByPhase(runId, "project-plan-paused"))
                .thenReturn(Optional.of(mock(com.spaceagent.platform.runtime.api.CheckpointView.class)));
        when(modelCalls.findByRunId(runId)).thenReturn(List.of());
        when(toolLedger.findByRunId(runId)).thenReturn(List.of());
        when(governance.listRequestedApprovals(any())).thenReturn(List.of());

        var result = coordinator.resume(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ResumeCommand(
                        "tenant", "user", projectId, taskPlanId, executionId, 7));

        assertThat(result.state()).isEqualTo(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.RUNNING);
        verify(modelCalls).findByRunId(runId);
        verify(toolLedger).findByRunId(runId);
        verify(governance).listRequestedApprovals(any());
        verify(planExecutions).resume(any());
        verify(planExecutions, org.mockito.Mockito.never()).block(any(), any());

        var unknownTool = mock(com.spaceagent.platform.tooling.api.ToolExecutionLedgerView.class);
        when(unknownTool.status()).thenReturn(
                com.spaceagent.platform.tooling.domain.ToolExecutionStatus.UNKNOWN);
        when(toolLedger.findByRunId(runId)).thenReturn(List.of(unknownTool));
        var blocked = mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView.class);
        when(blocked.state()).thenReturn(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.BLOCKED);
        when(planExecutions.block(any(), any())).thenReturn(blocked);

        var cancelled = coordinator.cancel(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.CancelCommand(
                        "tenant", "user", projectId, taskPlanId, executionId, 7, "stop"));

        assertThat(cancelled.state()).isEqualTo(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.BLOCKED);
        verify(planExecutions).block(any(),
                org.mockito.ArgumentMatchers.eq("PROJECT_PLAN_EXECUTION_UNKNOWN_EFFECT"));
        verify(planExecutions, org.mockito.Mockito.never()).requestCancel(any());
    }

    @Test void executesToolTestCompletionReviewAndMergeAsOneDurableJob(){
        Instant now=Instant.parse("2026-09-06T08:00:00Z");ObjectMapper json=new ObjectMapper();
        var jobs=new ProjectCodingJobApplicationService(new InMemoryProjectCodingJobRepository(),new UuidGenerator(),()->now,json);
        var directories=mock(ProjectDirectoryApplicationApi.class);var sources=mock(SourceRepositoryApplicationApi.class);
        var execution=mock(TaskExecutionApplicationApi.class);var tasks=mock(TaskApplicationApi.class);
        var plans=mock(TaskPlanApplicationApi.class);var blueprints=mock(ProjectBlueprintApplicationApi.class);
        var conversations=mock(ConversationApplicationApi.class);var agents=mock(AgentApplicationApi.class);
        var workspaces=mock(WorkspaceApplicationApi.class);
        var coding=mock(CodingRuntimeApplicationApi.class);var runtime=mock(RuntimeApplicationApi.class);
        var coordination=mock(com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi.class);
        var tools=mock(RuntimeToolExecutionApplicationApi.class);var catalog=mock(RuntimeCapabilityCatalogApplicationApi.class);
        var pools=mock(ModelPoolApplicationApi.class);var inference=mock(InferenceExecutionApi.class);
        var modelCalls=mock(com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi.class);
        var toolLedger=mock(com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi.class);
        var governance=mock(com.spaceagent.platform.governance.api.GovernanceApplicationApi.class);
        var artifacts=mock(ArtifactApplicationApi.class);var collaboration=mock(MultiAgentCollaborationApplicationApi.class);
        var merges=mock(ReviewedSourceMergeApplicationApi.class);
        var handoffs=mock(ProjectRunHandoffApplicationApi.class);
        var recovery=mock(ProjectRecoveryApplicationApi.class);
        var memory=mock(MemoryApplicationApi.class);
        var planExecutions=mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.class);
        var telemetry=RuntimeOperationalTelemetry.noop();
        when(handoffs.findByTargetCodingJobId(any())).thenReturn(Optional.empty());
        when(memory.recall(any())).thenReturn(List.of());
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:coding_coordinator;DB_CLOSE_DELAY=-1","sa","");
        var coordinator=new ProjectCodingCoordinator(jobs,planExecutions,directories,sources,execution,tasks,plans,
                blueprints,conversations,agents,workspaces,coding,runtime,coordination,tools,catalog,pools,
                inference,modelCalls,toolLedger,governance,artifacts,collaboration,merges,handoffs,recovery,memory,telemetry,json,
                new DataSourceTransactionManager(ds));
        var waveRepository=new com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectPlanWaveConcurrencyRepository();
        var barrierRepository=new com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectPlanMergeBarrierRepository();
        var barrierApplication=new ProjectPlanMergeBarrierApplicationService(
                barrierRepository,new UuidGenerator(),()->now);
        jobs.setMergeBarrierRepository(barrierRepository);
        coordinator.setWaveConcurrency(waveRepository,()->now);
        coordinator.setMergeBarrier(barrierApplication,mock(com.spaceagent.platform.integration.api.ProjectReconciliationIntegrationApi.class));
        String project=id(),directory=id(),conversation=id(),source=id(),root=id(),task=id(),plan=id(),step=id(),agent=id(),version=id(),reviewer=id(),workspace=id(),run=id(),reviewerRun=id(),executionId=id();
        var queued=jobs.enqueue(new ProjectCodingJobApplicationApi.EnqueueCommand("tenant","user",project,directory,conversation,source,root,task,plan,executionId,step,agent,version,reviewer,"main","coordinator-001",reviewer));
        var runningExecution=mock(
                com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView.class);
        when(runningExecution.id()).thenReturn(executionId);
        when(runningExecution.tenantId()).thenReturn("tenant");
        when(runningExecution.ownerId()).thenReturn("user");
        when(runningExecution.projectId()).thenReturn(project);
        when(runningExecution.taskPlanId()).thenReturn(plan);
        when(runningExecution.state()).thenReturn(
                com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.RUNNING);
        when(planExecutions.begin(any())).thenReturn(runningExecution);
        when(planExecutions.get(any())).thenReturn(runningExecution);
        when(directories.get(any())).thenReturn(new ProjectDirectoryApplicationApi.DirectoryView(
                directory,"tenant",project,source,"repo",".",false,
                ProjectDirectoryState.ACTIVE,now,now));
        when(sources.get(any())).thenReturn(new SourceRepositoryView(source,project,null,null,null,
                "repo","repo","https://example.com/repo.git",null,"main",
                SourceRepositoryType.GIT,SourceRepositoryState.READY,
                SourceRepositoryVisibility.PUBLIC,"user",now,now));
        when(execution.resolve(any())).thenReturn(new TaskExecutionReferenceView(project,root,task,
                plan,step,TaskPlanStatus.ACTIVE,PlanStepState.PENDING,TaskState.PENDING));
        when(conversations.find(conversation)).thenReturn(Optional.of(new ConversationView(
                conversation,project,directory,null,root,"tenant","user",agent,"Coding",
                ConversationStatus.ACTIVE,now,now)));
        AgentDefinitionView coder=mock(AgentDefinitionView.class);
        when(coder.tenantId()).thenReturn("tenant");when(coder.ownerId()).thenReturn("user");
        when(coder.status()).thenReturn(com.spaceagent.platform.agent.domain.AgentDefinitionStatus.ACTIVE);
        when(coder.id()).thenReturn(agent);when(agents.findById(agent)).thenReturn(Optional.of(coder));
        when(workspaces.list(any())).thenReturn(List.of());
        when(workspaces.provision(any())).thenReturn(new WorkspaceApplicationApi.WorkspaceView(workspace,project,directory,task,source,null,"execution:"+executionId+":plan-step:"+step,WorkspaceMode.MANAGED_GIT,id(),"main","branch","managed", "a".repeat(40),true,WorkspaceState.READY,null,1,now,now));
        when(coding.start(any())).thenReturn(new CodingRuntimeApplicationApi.CodingRunView(run,workspace,"IN_PROGRESS",List.of()));
        when(tasks.getTask(any())).thenReturn(new TaskView(task,project,root,"Child","Implement",null,List.of(),List.of("tests pass"),null,TaskState.PENDING,now,now));
        var planStep=new PlanStepView(step,"backend",0,task,List.of(),null,agent,"implementation",List.of("tests pass"),false,PlanStepState.PENDING,now,now);
        when(plans.getPlan(any())).thenReturn(new TaskPlanView(plan,project,root,1,TaskPlanStatus.ACTIVE,version,"user","user",now,List.of(planStep),now,now));
        when(blueprints.list(any())).thenReturn(List.of());
        var config=config(version,agent,List.of("coding_write_file","coding_run_command"));
        var reviewerConfig=config(reviewer,reviewer,List.of());
        var currentConfigurations=mock(AgentCurrentConfigurationApplicationApi.class);
        var currentConfiguration=mock(AgentCurrentConfigurationApplicationApi.ConfigurationView.class);
        when(currentConfiguration.agentRevision()).thenReturn(1L);
        when(currentConfigurations.requireCurrent(any(),any(),any())).thenReturn(currentConfiguration);
        var runConfigurations=mock(AgentRunConfigurationSnapshotApplicationApi.class);
        var codingSnapshot=snapshot(config,now);
        var reviewerSnapshot=snapshot(reviewerConfig,now);
        when(runConfigurations.require("tenant","user",run)).thenReturn(codingSnapshot);
        when(runConfigurations.require("tenant","user",reviewerRun)).thenReturn(reviewerSnapshot);
        coordinator.setAgentConfigurationBoundaries(currentConfigurations,runConfigurations);
        var capability=new RuntimeCapabilityCatalogApplicationApi.CapabilityView("coding_write_file","write","write", "CODING",Map.of(),true,false,false,true);
        var commandCapability=new RuntimeCapabilityCatalogApplicationApi.CapabilityView("coding_run_command","test","test", "CODING",Map.of(),true,false,false,true);
        when(catalog.resolveTools(any())).thenReturn(List.of(capability,commandCapability));
        var skillCapability=new RuntimeCapabilityCatalogApplicationApi.SkillCapabilityView(
                "skill-version","skill","Coding discipline",1,"a".repeat(64),
                "Preserve the skill-marker in model context.",List.of());
        when(catalog.resolvePinnedSkills(any(),any(),any())).thenReturn(List.of(skillCapability));
        AtomicInteger modelRound=new AtomicInteger();
        List<InferenceExecutionApi.InferenceExecutionCommand> modelCommands=new ArrayList<>();
        when(inference.execute(any())).thenAnswer(invocation->{var command=invocation.getArgument(0,InferenceExecutionApi.InferenceExecutionCommand.class);modelCommands.add(command);int round=modelRound.getAndIncrement();return switch(round){
            case 0->new InferenceExecutionApi.InferenceExecutionResult("",1,1,List.of(new InferenceExecutionApi.InferenceToolCall("write-1","coding_write_file","{\"workspaceId\":\""+workspace+"\",\"path\":\"README.md\",\"content\":\"done\"}")));
            case 1->new InferenceExecutionApi.InferenceExecutionResult("",1,1,List.of(new InferenceExecutionApi.InferenceToolCall("test-1","coding_run_command","{\"workspaceId\":\""+workspace+"\",\"executable\":\"mvn\",\"arguments\":[\"test\"],\"timeoutSeconds\":60}")));
            case 2->new InferenceExecutionApi.InferenceExecutionResult("{\"summary\":\"done\",\"commitMessage\":\"implement\",\"acceptance\":[{\"criterion\":\"tests pass\",\"passed\":true,\"details\":\"mvn test\"}]}",1,1);
            default->new InferenceExecutionApi.InferenceExecutionResult("{\"decision\":\"APPROVED\",\"evidence\":\"patch and tests verified\"}",1,1);};});
        List<ArtifactApplicationApi.ArtifactView> evidence=new ArrayList<>();
        var testArtifact=artifact(id(),run,workspace,ArtifactType.TEST_REPORT,"hash","test","{\"exitCode\":0}",now);
        when(tools.execute(any())).thenAnswer(invocation->{var c=invocation.getArgument(0,RuntimeToolExecutionApplicationApi.ExecuteRuntimeToolCommand.class);if(c.toolId().equals("coding_run_command"))evidence.add(testArtifact);return new RuntimeToolExecutionApplicationApi.RuntimeToolResult(c.toolCallId(),c.toolId(),"SUCCEEDED","ok",null,null);});
        var patch=artifact(id(),run,workspace,ArtifactType.PATCH,"sha256:"+"b".repeat(64),"patch","{\"patch\":\"diff\"}",now.plusSeconds(1));
        var commit=artifact(id(),run,workspace,ArtifactType.COMMIT_PROPOSAL,patch.contentHash(),"implement","{\"baseHead\":\""+"a".repeat(40)+"\",\"patchArtifactId\":\""+patch.id()+"\"}",now.plusSeconds(2));
        when(artifacts.byRun(run)).thenAnswer(ignored->List.copyOf(evidence));
        when(coding.prepareCompletion(any())).thenAnswer(ignored->{evidence.add(patch);evidence.add(commit);return new CodingRuntimeApplicationApi.CodingRunView(run,workspace,"AWAITING_REVIEW",evidence.stream().map(ArtifactApplicationApi.ArtifactView::id).toList());});
        AtomicInteger steps=new AtomicInteger();when(runtime.startStep(any())).thenAnswer(ignored->new RunStepView(id(),run,steps.incrementAndGet(),"step",RunStepState.PENDING,now,null));
        when(runtime.findRun(run)).thenReturn(Optional.of(agentRun(run,agent,version,conversation,project,directory,workspace,task,plan,step,now)));
        when(runtime.startRun(any())).thenReturn(agentRun(reviewerRun,reviewerConfig.agentId(),reviewer,conversation,null,null,null,null,null,null,now));
        when(runtime.findRun(reviewerRun)).thenReturn(Optional.of(agentRun(reviewerRun,reviewerConfig.agentId(),reviewer,conversation,null,null,null,null,null,null,now)));
        when(runtime.findSteps(reviewerRun)).thenReturn(List.of());
        when(collaboration.reviews("user",run)).thenReturn(List.of());
        var pendingReview=new MultiAgentCollaborationApplicationApi.ReviewView(id(),run,run,reviewer,List.of(patch.id(),commit.id(),testArtifact.id()),AgentReviewDecision.PENDING,null,now,null);
        when(collaboration.requestExecutionReview(any())).thenReturn(pendingReview);
        when(collaboration.decideExecutionReview(any())).thenReturn(new MultiAgentCollaborationApplicationApi.ReviewView(pendingReview.id(),run,run,reviewer,pendingReview.artifactIds(),AgentReviewDecision.APPROVED,"verified",now,now));
        SourceMergeApplicationApi.SourceMergeView merge=mock(SourceMergeApplicationApi.SourceMergeView.class);when(merge.id()).thenReturn(id());when(merge.state()).thenReturn(SourceMergeState.READY);when(merges.prepare(any())).thenReturn(merge);when(merges.get(any())).thenReturn(merge);SourceMergeApplicationApi.SourceMergeView applied=mock(SourceMergeApplicationApi.SourceMergeView.class);when(applied.state()).thenReturn(SourceMergeState.APPLIED_LOCAL);when(merges.apply(any())).thenReturn(applied);

        assertThat(coordinator.runOnce("worker",900,3,10,2)).isTrue();
        var result=jobs.get(new ProjectCodingJobApplicationApi.Query("tenant","user",project,plan,step,queued.id()));
        assertThat(result.state()).withFailMessage("coding job failed with %s", result.safeErrorCode())
                .isEqualTo(ProjectCodingJobState.COMPLETED);
        assertThat(result.patchArtifactId()).isEqualTo(patch.id());
        assertThat(result.sourceMergeId()).isEqualTo(merge.id());
        assertThat(barrierRepository.find(executionId).orElseThrow().state()).isEqualTo(com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrier.State.ACTIVE);
        var completedStep=new PlanStepView(step,"backend",0,task,List.of(),null,agent,"implementation",List.of("tests pass"),false,PlanStepState.COMPLETED,now,now);
        when(plans.getPlan(any())).thenReturn(new TaskPlanView(plan,project,root,1,TaskPlanStatus.ACTIVE,version,"user","user",now,List.of(completedStep),now,now));
        assertThat(coordinator.drainMergeBarrierOnce("barrier-worker",900,3)).isTrue();
        assertThat(barrierRepository.find(executionId).orElseThrow().state()).isEqualTo(com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrier.State.COMPLETED);
        verify(merges).apply(argThat(command->command.mergeId().equals(merge.id())));
        assertThat(modelRound).hasValue(4);
        assertThat(modelCommands).allSatisfy(command -> assertThat(command.messages())
                .anySatisfy(message -> assertThat(message.content()).contains("skill-marker")));
        ArgumentCaptor<CreateCheckpointCommand> checkpoints =
                ArgumentCaptor.forClass(CreateCheckpointCommand.class);
        verify(runtime,atLeast(4)).createCheckpoint(checkpoints.capture());
        verify(planExecutions).begin(any());
        verify(workspaces).provision(argThat(command ->
                ("execution:" + executionId + ":plan-step:" + step).equals(command.isolationKey())));
        assertThat(checkpoints.getAllValues()).allSatisfy(checkpoint ->
                assertThat(checkpoint.stateSnapshot())
                        .contains("skill-context-bound","skill-version","a".repeat(64))
                        .doesNotContain("skill-marker","Coding discipline"));
    }
    private static AgentRunConfigurationSnapshotApplicationApi.SnapshotView snapshot(
            AgentExecutionConfigurationView config,Instant now){
        var snapshot=mock(AgentRunConfigurationSnapshotApplicationApi.SnapshotView.class);
        when(snapshot.state()).thenReturn("SNAPSHOTTED");
        when(snapshot.agentId()).thenReturn(config.agentId());
        when(snapshot.agentRevision()).thenReturn(1L);
        when(snapshot.configHash()).thenReturn(config.configHash());
        when(snapshot.modelPoolId()).thenReturn(config.modelPoolId());
        when(snapshot.modelProviderId()).thenReturn(config.modelProviderId());
        when(snapshot.modelId()).thenReturn(config.modelId());
        when(snapshot.systemPrompt()).thenReturn(config.systemPrompt());
        when(snapshot.temperature()).thenReturn(config.temperature());
        when(snapshot.maxContextTokens()).thenReturn(config.maxContextTokens());
        when(snapshot.maxOutputTokens()).thenReturn(config.maxOutputTokens());
        when(snapshot.maxTurns()).thenReturn(config.maxTurns());
        when(snapshot.memoryEnabled()).thenReturn(config.memoryEnabled());
        when(snapshot.ragEnabled()).thenReturn(config.ragEnabled());
        when(snapshot.networkEnabled()).thenReturn(config.networkEnabled());
        when(snapshot.knowledgeBaseIds()).thenReturn(config.knowledgeBaseIds());
        when(snapshot.enabledToolIds()).thenReturn(config.enabledToolIds());
        when(snapshot.skillIds()).thenReturn(config.skillIds());
        when(snapshot.permissionMode()).thenReturn(config.permissionMode());
        when(snapshot.mcpBindings()).thenReturn(List.of());
        when(snapshot.sourceUpdatedBy()).thenReturn("user");
        when(snapshot.sourceUpdatedAt()).thenReturn(now);
        return snapshot;
    }
    private static AgentExecutionConfigurationView config(String ignored,String agent,List<String> tools){return new AgentExecutionConfigurationView(agent,"hash",null,"provider","model","system",0.0,32000,4000,10,false,false,List.of(),tools,List.of("skill-version"),"STANDARD",false,1,List.of());}
    private static AgentDefinitionView activeAgent(String id, Instant now) {
        AgentDefinitionView value = mock(AgentDefinitionView.class);
        when(value.id()).thenReturn(id);
        when(value.tenantId()).thenReturn("tenant");
        when(value.ownerId()).thenReturn("user");
        when(value.status()).thenReturn(
                com.spaceagent.platform.agent.domain.AgentDefinitionStatus.ACTIVE);
        return value;
    }
    private static ArtifactApplicationApi.ArtifactView artifact(String id,String run,String workspace,ArtifactType type,String hash,String summary,String metadata,Instant now){return new ArtifactApplicationApi.ArtifactView(id,"project","task",run,workspace,type,type.name(),null,hash,summary,metadata,now);}
    private static AgentRunView agentRun(String id,String agent,String snapshotId,String conversation,String project,String directory,String workspace,String task,String plan,String step,Instant now){return new AgentRunView(id,agent,snapshotId,"tenant","user",conversation,project,directory,workspace,task,plan,step,null,0,AgentRunState.IN_PROGRESS,null,now,now,null);}
    private static String id(){return UUID.randomUUID().toString();}
}
