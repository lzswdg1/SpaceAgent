package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.runtime.api.AgentExecutionConfigurationView;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.governance.api.GovernanceApprovalRequiredException;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.integration.api.ReviewedSourceMergeApplicationApi;
import com.spaceagent.platform.integration.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.platform.integration.api.ProjectReconciliationIntegrationApi;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.memory.api.MemoryRecallCommand;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import com.spaceagent.platform.project.api.GetSourceRepositoryQuery;
import com.spaceagent.platform.project.api.GetTaskPlanQuery;
import com.spaceagent.platform.project.api.GetTaskQuery;
import com.spaceagent.platform.project.api.ProjectBlueprintApplicationApi;
import com.spaceagent.platform.project.api.ProjectDirectoryApplicationApi;
import com.spaceagent.platform.project.api.SourceRepositoryApplicationApi;
import com.spaceagent.platform.project.api.SourceMergeApplicationApi;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskExecutionApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanView;
import com.spaceagent.platform.project.api.TaskPlanAction;
import com.spaceagent.platform.project.api.TaskPlanActionCommand;
import com.spaceagent.platform.project.api.PlanStepExecutionAction;
import com.spaceagent.platform.project.api.TransitionPlanStepExecutionCommand;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.ProjectBlueprintStatus;
import com.spaceagent.platform.project.domain.ProjectDirectoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceMergeState;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.CodingRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.CancelAgentRunFencedCommand;
import com.spaceagent.platform.runtime.api.CompleteAgentRunCommand;
import com.spaceagent.platform.runtime.api.CompleteRunStepCommand;
import com.spaceagent.platform.runtime.api.CreateCheckpointCommand;
import com.spaceagent.platform.runtime.api.FailAgentRunCommand;
import com.spaceagent.platform.runtime.api.FailRunStepCommand;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRecoveryApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.AgentRunConfigurationSnapshotApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolExecutionApplicationApi;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartRunStepCommand;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.domain.ProjectPlanReadyWave;
import com.spaceagent.platform.runtime.domain.ProjectPlanWaveConcurrency;
import com.spaceagent.platform.runtime.domain.ProjectPlanWaveConcurrencyRepository;
import com.spaceagent.platform.runtime.api.ProjectPlanMergeBarrierApplicationApi;
import com.spaceagent.platform.runtime.domain.RuntimeOperationalTelemetry;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi.SkillCapabilityView;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProjectCodingCoordinator implements ProjectPlanExecutionApplicationApi {
    private static final Set<String> CODING_TOOLS=Set.of("file_read","file_list","git_status",
            "git_diff","document_read","document_write","coding_write_file",
            "coding_delete_file","coding_run_command");
    private final ProjectCodingJobApplicationApi jobs;
    private final com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi planExecutions;
    private final ProjectDirectoryApplicationApi directories;
    private final SourceRepositoryApplicationApi sources; private final TaskExecutionApplicationApi execution;
    private final TaskApplicationApi tasks; private final TaskPlanApplicationApi plans;
    private final ProjectBlueprintApplicationApi blueprints; private final ConversationApplicationApi conversations;
    private final AgentApplicationApi agents;
    private final WorkspaceApplicationApi workspaces; private final CodingRuntimeApplicationApi coding;
    private final RuntimeApplicationApi runtime; private final RuntimeCoordinationApplicationApi coordination;
    private final RuntimeToolExecutionApplicationApi tools;
    private final RuntimeCapabilityCatalogApplicationApi catalog; private final ModelPoolApplicationApi pools;
    private final InferenceExecutionApi inference; private final ModelCallLedgerApplicationApi modelCalls;
    private final ToolExecutionLedgerApplicationApi toolLedger; private final GovernanceApplicationApi governance;
    private final ArtifactApplicationApi artifacts;
    private final MultiAgentCollaborationApplicationApi collaboration;
    private final ReviewedSourceMergeApplicationApi merges; private final ObjectMapper json;
    private final ProjectRunHandoffApplicationApi projectHandoffs;
    private final ProjectRecoveryApplicationApi recovery;
    private final MemoryApplicationApi memory;
    private final RuntimeOperationalTelemetry telemetry;
    private final TransactionTemplate transactions;
    private ProjectPlanStepAssignmentApplicationApi assignments;
    private ProjectPlanWaveConcurrencyRepository waveConcurrency;
    private TimeProvider waveTime;
    private ProjectPlanMergeBarrierApplicationApi mergeBarriers;
    private ProjectReconciliationIntegrationApi reconciliation;
    private AgentCurrentConfigurationApplicationApi currentConfigurations;
    private AgentRunConfigurationSnapshotApplicationApi runConfigurations;

    public ProjectCodingCoordinator(ProjectCodingJobApplicationApi jobs,
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi planExecutions,
            ProjectDirectoryApplicationApi directories,SourceRepositoryApplicationApi sources,
            TaskExecutionApplicationApi execution,TaskApplicationApi tasks,TaskPlanApplicationApi plans,
            ProjectBlueprintApplicationApi blueprints,ConversationApplicationApi conversations,
            AgentApplicationApi agents,
            WorkspaceApplicationApi workspaces,CodingRuntimeApplicationApi coding,
            RuntimeApplicationApi runtime,RuntimeCoordinationApplicationApi coordination,
            RuntimeToolExecutionApplicationApi tools,
            RuntimeCapabilityCatalogApplicationApi catalog,ModelPoolApplicationApi pools,
            InferenceExecutionApi inference,ModelCallLedgerApplicationApi modelCalls,
            ToolExecutionLedgerApplicationApi toolLedger,GovernanceApplicationApi governance,
            ArtifactApplicationApi artifacts,
            MultiAgentCollaborationApplicationApi collaboration,ReviewedSourceMergeApplicationApi merges,
            ProjectRunHandoffApplicationApi projectHandoffs,
            ProjectRecoveryApplicationApi recovery,MemoryApplicationApi memory,
            RuntimeOperationalTelemetry telemetry,
            ObjectMapper json,PlatformTransactionManager manager){
        this.jobs=jobs;this.planExecutions=planExecutions;this.directories=directories;
        this.sources=sources;this.execution=execution;
        this.tasks=tasks;this.plans=plans;this.blueprints=blueprints;this.conversations=conversations;
        this.agents=agents;this.workspaces=workspaces;this.coding=coding;
        this.runtime=runtime;this.coordination=coordination;this.tools=tools;
        this.catalog=catalog;this.pools=pools;this.inference=inference;
        this.modelCalls=modelCalls;this.toolLedger=toolLedger;this.governance=governance;
        this.artifacts=artifacts;this.collaboration=collaboration;this.merges=merges;
        this.projectHandoffs=projectHandoffs;this.recovery=recovery;this.memory=memory;
        this.telemetry=telemetry;this.json=json;
        this.transactions=new TransactionTemplate(manager);
    }

    @Autowired(required = false)
    void setAssignments(ProjectPlanStepAssignmentApplicationApi assignments) {
        this.assignments = assignments;
    }

    @Autowired(required = false)
    public void setWaveConcurrency(ProjectPlanWaveConcurrencyRepository repository,TimeProvider timeProvider){this.waveConcurrency=repository;this.waveTime=timeProvider;}

    @Autowired(required=false)
    public void setMergeBarrier(ProjectPlanMergeBarrierApplicationApi application,
                                ProjectReconciliationIntegrationApi reconciliation) {
        this.mergeBarriers = application;
        this.reconciliation = reconciliation;
    }

    @Autowired(required=false)
    public void setAgentConfigurationBoundaries(
            AgentCurrentConfigurationApplicationApi currentConfigurations,
            AgentRunConfigurationSnapshotApplicationApi runConfigurations) {
        this.currentConfigurations = currentConfigurations;
        this.runConfigurations = runConfigurations;
    }

    public ProjectCodingJobApplicationApi.JobView enqueue(ProjectCodingJobApplicationApi.EnqueueCommand c){
        var sandbox=catalog.catalog().sandbox();if(!sandbox.containerized())throw business("PROJECT_CODING_SANDBOX_REQUIRED",HttpStatus.SERVICE_UNAVAILABLE);
        var directory=directories.get(new ProjectDirectoryApplicationApi.Query(c.tenantId(),c.ownerId(),c.projectId(),c.projectDirectoryId()));
        if(directory.state()!=ProjectDirectoryState.ACTIVE||!".".equals(directory.relativePath())
                ||!c.sourceRepositoryId().equals(directory.sourceRepositoryId()))throw business("PROJECT_CODING_DIRECTORY_INVALID",HttpStatus.CONFLICT);
        var source=sources.get(new GetSourceRepositoryQuery(c.tenantId(),c.ownerId(),c.projectId(),c.sourceRepositoryId()));
        if(source.state()!=SourceRepositoryState.READY||source.type()==SourceRepositoryType.LOCAL)throw business("PROJECT_CODING_SOURCE_INVALID",HttpStatus.CONFLICT);
        var reference=execution.resolve(new com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery(
                c.tenantId(),c.ownerId(),c.projectId(),c.taskId(),c.taskPlanId(),c.planStepId()));
        if(!reference.rootTaskId().equals(c.rootTaskId())||!Set.of(PlanStepState.PENDING,PlanStepState.READY).contains(reference.planStepState()))throw business("PROJECT_CODING_STEP_INVALID",HttpStatus.CONFLICT);
        var plan=plans.getPlan(new GetTaskPlanQuery(c.tenantId(),c.ownerId(),c.projectId(),c.rootTaskId(),c.taskPlanId()));
        var step=plan.steps().stream().filter(v->v.id().equals(c.planStepId())).findFirst().orElseThrow();
        if(step.preferredAgentId()!=null&&!step.preferredAgentId().equals(c.agentId()))throw business("PROJECT_CODING_AGENT_MISMATCH",HttpStatus.CONFLICT);
        var conversation=conversations.find(c.conversationId()).filter(v->v.tenantId().equals(c.tenantId()))
                .filter(v->v.userId().equals(c.ownerId())).filter(v->c.projectId().equals(v.projectId()))
                .filter(v->c.projectDirectoryId().equals(v.projectDirectoryId()))
                .filter(v->v.status()==ConversationStatus.ACTIVE).orElseThrow(()->business("PROJECT_CODING_CONVERSATION_INVALID",HttpStatus.NOT_FOUND));
        String reviewerAgentId = c.reviewerAgentId();
        if (reviewerAgentId == null) throw business("ASSIGNMENT_REQUIRED",HttpStatus.CONFLICT);
        validateCurrent(c.tenantId(), c.ownerId(), c.agentId());
        validateCurrent(c.tenantId(), c.ownerId(), reviewerAgentId);
        if(reviewerAgentId.equals(c.agentId()))throw business("PROJECT_CODING_REVIEWER_NOT_DISTINCT",HttpStatus.CONFLICT);
        return jobs.enqueue(new ProjectCodingJobApplicationApi.EnqueueCommand(
                c.tenantId(), c.ownerId(), c.projectId(), c.projectDirectoryId(),
                c.conversationId(), c.sourceRepositoryId(), c.rootTaskId(), c.taskId(),
                c.taskPlanId(), c.executionId(), c.planStepId(), c.agentId(),
                null, null, c.baseRef(), c.idempotencyKey(), reviewerAgentId));
    }

    @Override
    @Transactional
    public DispatchView dispatch(DispatchCommand command) {
        var plan = plans.getPlan(new GetTaskPlanQuery(
                command.tenantId(), command.ownerId(), command.projectId(),
                command.rootTaskId(), command.taskPlanId()));
        if (plan.status() != TaskPlanStatus.APPROVED
                && plan.status() != TaskPlanStatus.ACTIVE
                && plan.status() != TaskPlanStatus.COMPLETED) {
            throw business("PROJECT_PLAN_NOT_EXECUTABLE", HttpStatus.CONFLICT);
        }
        if (plan.status() == TaskPlanStatus.COMPLETED) {
            var existing = planExecutions.getByTaskPlan(
                    new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi
                            .QueryByTaskPlan(
                            command.tenantId(), command.ownerId(), command.taskPlanId()))
                    .orElseThrow(() -> business(
                            "PROJECT_PLAN_EXECUTION_NOT_FOUND", HttpStatus.NOT_FOUND));
            return new DispatchView(existing.id(), plan.id(), plan.status().name(), null, false);
        }
        validateExecutionBinding(command);
        var planExecution = planExecutions.start(executionStart(command));
        if (planExecution.state() == com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.READY
                || planExecution.state() == com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.RUNNING) {
            planExecution = planExecutions.begin(executionTransition(command, planExecution.id()));
        }
        if (plan.status() == TaskPlanStatus.APPROVED) {
            plan = plans.transition(new TaskPlanActionCommand(
                    command.tenantId(), command.ownerId(), command.projectId(),
                    command.rootTaskId(), command.taskPlanId(), TaskPlanAction.ACTIVATE));
        }
        return dispatchNext(command, plan, planExecution);
    }

    private void validateExecutionBinding(DispatchCommand command) {
        var sandbox = catalog.catalog().sandbox();
        if (!sandbox.containerized()) {
            throw business("PROJECT_CODING_SANDBOX_REQUIRED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        var directory = directories.get(new ProjectDirectoryApplicationApi.Query(
                command.tenantId(), command.ownerId(), command.projectId(),
                command.projectDirectoryId()));
        if (directory.state() != ProjectDirectoryState.ACTIVE
                || !".".equals(directory.relativePath())
                || !command.sourceRepositoryId().equals(directory.sourceRepositoryId())) {
            throw business("PROJECT_CODING_DIRECTORY_INVALID", HttpStatus.CONFLICT);
        }
        var source = sources.get(new GetSourceRepositoryQuery(
                command.tenantId(), command.ownerId(), command.projectId(),
                command.sourceRepositoryId()));
        if (source.state() != SourceRepositoryState.READY
                || source.type() == SourceRepositoryType.LOCAL) {
            throw business("PROJECT_CODING_SOURCE_INVALID", HttpStatus.CONFLICT);
        }
        conversations.find(command.conversationId())
                .filter(value -> value.tenantId().equals(command.tenantId()))
                .filter(value -> value.userId().equals(command.ownerId()))
                .filter(value -> command.projectId().equals(value.projectId()))
                .filter(value -> command.projectDirectoryId().equals(value.projectDirectoryId()))
                .filter(value -> value.status() == ConversationStatus.ACTIVE)
                .orElseThrow(() -> business(
                        "PROJECT_CODING_CONVERSATION_INVALID", HttpStatus.NOT_FOUND));
        validateCurrent(command.tenantId(), command.ownerId(), command.agentId());
        String reviewerAgentId = command.reviewerAgentId();
        if (reviewerAgentId == null) throw business("ASSIGNMENT_REQUIRED",HttpStatus.CONFLICT);
        validateCurrent(command.tenantId(), command.ownerId(), reviewerAgentId);
        if (reviewerAgentId.equals(command.agentId())) {
            throw business("PROJECT_CODING_REVIEWER_NOT_DISTINCT", HttpStatus.CONFLICT);
        }
    }

    public com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView pause(
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.PauseCommand command) {
        var current = executionForControl(
                command.tenantId(), command.ownerId(), command.projectId(), command.taskPlanId(),
                command.executionId());
        if (current.revision() != command.expectedRevision()) {
            throw business("PROJECT_PLAN_EXECUTION_STALE_REVISION", HttpStatus.CONFLICT);
        }
        return planExecutions.requestPause(command);
    }

    public com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView resume(
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ResumeCommand command) {
        var current = executionForControl(
                command.tenantId(), command.ownerId(), command.projectId(), command.taskPlanId(),
                command.executionId());
        if (current.state()
                != com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSED) {
            throw business("PROJECT_PLAN_EXECUTION_RESUME_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        if (current.revision() != command.expectedRevision()) {
            throw business("PROJECT_PLAN_EXECUTION_STALE_REVISION", HttpStatus.CONFLICT);
        }
        try {
            validateResume(command, current);
        } catch (BusinessException error) {
            return planExecutions.block(resumeTransition(command), safe(error.getCode()));
        }
        return planExecutions.resume(command);
    }

    public com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView cancel(
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.CancelCommand command) {
        var current = executionForControl(
                command.tenantId(), command.ownerId(), command.projectId(), command.taskPlanId(),
                command.executionId());
        if (current.revision() != command.expectedRevision()) {
            throw business("PROJECT_PLAN_EXECUTION_STALE_REVISION", HttpStatus.CONFLICT);
        }
        if (current.state() == com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.BLOCKED) {
            throw business("PROJECT_PLAN_EXECUTION_RECONCILIATION_REQUIRED", HttpStatus.CONFLICT);
        }
        List<ProjectCodingJobApplicationApi.JobView> activeJobs = current.activeJobs().stream()
                .map(active -> jobs.get(new ProjectCodingJobApplicationApi.Query(
                        command.tenantId(), command.ownerId(), command.projectId(),
                        command.taskPlanId(), active.planStepId(), active.id())))
                .toList();
        for (ProjectCodingJobApplicationApi.JobView job : activeJobs) {
            String blocker = cancellationBlocker(job);
            if (blocker != null) {
                return planExecutions.block(cancelTransition(command), blocker);
            }
        }
        var cancelling = planExecutions.requestCancel(command);
        boolean readyToAcknowledge = true;
        for (ProjectCodingJobApplicationApi.JobView job : activeJobs) {
            if (job.state() == ProjectCodingJobState.RUNNING) {
                readyToAcknowledge = false;
                continue;
            }
            if (!cancelRunFenced(job, "project-plan-cancel:" + command.executionId())) {
                readyToAcknowledge = false;
                continue;
            }
            jobs.cancel(new ProjectCodingJobApplicationApi.CancelCommand(
                    job.tenantId(), job.ownerId(), job.projectId(), job.taskPlanId(),
                    job.planStepId(), job.id(), job.revision(), command.reason()));
            if (job.codingRunId() == null) cancelProjectStep(job);
        }
        if (!readyToAcknowledge) return cancelling;
        cancelTaskPlan(current);
        return planExecutions.acknowledgeCancel(cancelTransition(command));
    }

    private com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView
            executionForControl(
            String tenantId, String ownerId, String projectId, String taskPlanId, String executionId) {
        return planExecutions.get(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.Query(
                        tenantId, ownerId, projectId, taskPlanId, executionId));
    }

    public int recoverControlTransitions(int limit) {
        int recovered = 0;
        for (var execution : planExecutions.controlTransitions(Math.max(1, Math.min(100, limit)))) {
            try {
                if (recoverControlTransition(execution)) recovered++;
            } catch (RuntimeException ignored) {
                // A newer request, active lease or concurrent replica remains authoritative.
            }
        }
        return recovered;
    }

    private boolean recoverControlTransition(
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView execution) {
        if (execution.state()
                == com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSING) {
            if (execution.activeJobs().stream()
                    .anyMatch(job -> job.state() == ProjectCodingJobState.RUNNING)) return false;
            boolean checkpointsPresent = execution.activeJobs().stream()
                    .filter(job -> job.codingRunId() != null)
                    .allMatch(job -> runtime.findLatestCheckpointByPhase(
                            job.codingRunId(), "project-plan-paused").isPresent());
            if (!checkpointsPresent) {
                planExecutions.block(controlTransition(execution),
                        "PROJECT_PLAN_EXECUTION_CHECKPOINT_MISSING");
                return true;
            }
            planExecutions.acknowledgePause(controlTransition(execution));
            return true;
        }
        if (execution.state()
                != com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.CANCELLING) {
            return false;
        }
        List<ProjectCodingJobApplicationApi.JobView> jobsToCancel = execution.activeJobs().stream()
                .map(active -> jobs.get(new ProjectCodingJobApplicationApi.Query(
                        execution.tenantId(), execution.ownerId(), execution.projectId(),
                        execution.taskPlanId(), active.planStepId(), active.id())))
                .toList();
        if (jobsToCancel.stream().anyMatch(job -> job.state() == ProjectCodingJobState.RUNNING)) {
            return false;
        }
        for (ProjectCodingJobApplicationApi.JobView job : jobsToCancel) {
            String blocker = cancellationBlocker(job);
            if (blocker != null) {
                planExecutions.block(controlTransition(execution), blocker);
                return true;
            }
            if (!cancelRunFenced(job, "project-plan-recovery:" + execution.id())) return false;
            jobs.cancel(new ProjectCodingJobApplicationApi.CancelCommand(
                    job.tenantId(), job.ownerId(), job.projectId(), job.taskPlanId(),
                    job.planStepId(), job.id(), job.revision(),
                    execution.safeErrorCode() == null ? "recovered cancellation" : execution.safeErrorCode()));
            if (job.codingRunId() == null) cancelProjectStep(job);
        }
        cancelTaskPlan(execution);
        planExecutions.acknowledgeCancel(controlTransition(execution));
        return true;
    }

    private void validateResume(
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ResumeCommand command,
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView current) {
        TaskPlanView plan = plans.getPlan(new GetTaskPlanQuery(
                command.tenantId(), command.ownerId(), command.projectId(),
                current.rootTaskId(), command.taskPlanId()));
        if (plan.status() != TaskPlanStatus.ACTIVE || !plan.id().equals(command.taskPlanId())) {
            throw business("PROJECT_PLAN_EXECUTION_PLAN_NOT_ACTIVE", HttpStatus.CONFLICT);
        }
        var active = current.activeJobs().stream()
                .filter(value -> value.state() == ProjectCodingJobState.PENDING)
                .findFirst().orElse(null);
        if (active == null) {
            throw business("PROJECT_PLAN_EXECUTION_ACTIVE_JOB_MISSING", HttpStatus.CONFLICT);
        }
        ProjectCodingJobApplicationApi.JobView job = jobs.get(
                new ProjectCodingJobApplicationApi.Query(
                        command.tenantId(), command.ownerId(), command.projectId(),
                        command.taskPlanId(), active.planStepId(), active.id()));
        if (!command.executionId().equals(job.executionId())
                || job.state() != ProjectCodingJobState.PENDING
                || !job.taskPlanId().equals(command.taskPlanId())) {
            throw business("PROJECT_PLAN_EXECUTION_JOB_SCOPE_INVALID", HttpStatus.CONFLICT);
        }
        var step = plan.steps().stream()
                .filter(value -> value.id().equals(job.planStepId()))
                .findFirst().orElse(null);
        if (step == null) {
            throw business("PROJECT_PLAN_EXECUTION_STEP_INVALID", HttpStatus.CONFLICT);
        }
        if (job.workspaceId() == null && job.codingRunId() == null) {
            if (!Set.of(PlanStepState.PENDING, PlanStepState.READY).contains(step.state())) {
                throw business("PROJECT_PLAN_EXECUTION_STEP_INVALID", HttpStatus.CONFLICT);
            }
            validateExecutionBinding(new DispatchCommand(
                    current.tenantId(), current.ownerId(), current.projectId(),
                    current.rootTaskId(), current.taskPlanId(), current.projectDirectoryId(),
                    current.conversationId(), current.sourceRepositoryId(), job.agentId(),
                    null, null, current.baseRef(), job.reviewerAgentId()));
            return;
        }
        if (job.workspaceId() == null || job.codingRunId() == null
                || step.state() != PlanStepState.IN_PROGRESS) {
            throw business("PROJECT_PLAN_EXECUTION_WORKSPACE_INVALID", HttpStatus.CONFLICT);
        }
        var workspace = workspaces.get(new WorkspaceApplicationApi.Query(
                command.tenantId(), command.ownerId(), command.projectId(), job.workspaceId()));
        if (workspace == null
                || !command.projectId().equals(workspace.projectId())
                || !job.projectDirectoryId().equals(workspace.projectDirectoryId())
                || !job.taskId().equals(workspace.taskId())
                || !job.sourceRepositoryId().equals(workspace.sourceRepositoryId())
                || workspace.mode() != WorkspaceMode.MANAGED_GIT
                || workspace.state() != WorkspaceState.READY
                || !workspace.writable()) {
            throw business("PROJECT_PLAN_EXECUTION_WORKSPACE_INVALID", HttpStatus.CONFLICT);
        }
        validateCurrent(command.tenantId(), command.ownerId(), job.agentId());
        var run = runtime.findRun(job.codingRunId()).orElse(null);
        if (run == null
                || !job.codingRunId().equals(run.id())
                || !command.tenantId().equals(run.tenantId())
                || !command.ownerId().equals(run.ownerId())
                || !command.projectId().equals(run.projectId())
                || !job.projectDirectoryId().equals(run.projectDirectoryId())
                || !job.workspaceId().equals(run.workspaceId())
                || !job.taskId().equals(run.taskId())
                || !job.taskPlanId().equals(run.taskPlanId())
                || !job.planStepId().equals(run.planStepId())
                || !job.agentId().equals(run.agentId())
                || Set.of(AgentRunState.COMPLETED, AgentRunState.FAILED,
                        AgentRunState.CANCELLED).contains(run.state())) {
            throw business("PROJECT_PLAN_EXECUTION_RUN_INVALID", HttpStatus.CONFLICT);
        }
        if (runtime.findLatestCheckpointByPhase(run.id(), "project-plan-paused").isEmpty()) {
            throw business("PROJECT_PLAN_EXECUTION_CHECKPOINT_MISSING", HttpStatus.CONFLICT);
        }
        var modelEvidence = modelCalls.findByRunId(run.id());
        if (modelEvidence.stream().anyMatch(value ->
                value.status() == com.spaceagent.platform.inference.domain.ModelCallStatus.UNKNOWN)) {
            throw business("PROJECT_PLAN_EXECUTION_UNKNOWN_EFFECT", HttpStatus.CONFLICT);
        }
        if (modelEvidence.stream().anyMatch(value ->
                value.status() == com.spaceagent.platform.inference.domain.ModelCallStatus.RUNNING)) {
            throw business("PROJECT_PLAN_EXECUTION_EFFECT_IN_FLIGHT", HttpStatus.CONFLICT);
        }
        var toolEvidence = toolLedger.findByRunId(run.id());
        if (toolEvidence.stream().anyMatch(value ->
                value.status() == com.spaceagent.platform.tooling.domain.ToolExecutionStatus.UNKNOWN)) {
            throw business("PROJECT_PLAN_EXECUTION_UNKNOWN_EFFECT", HttpStatus.CONFLICT);
        }
        if (toolEvidence.stream().anyMatch(value ->
                value.status() == com.spaceagent.platform.tooling.domain.ToolExecutionStatus.PENDING
                        || value.status() == com.spaceagent.platform.tooling.domain.ToolExecutionStatus.RUNNING)) {
            throw business("PROJECT_PLAN_EXECUTION_EFFECT_IN_FLIGHT", HttpStatus.CONFLICT);
        }
        var approvals = governance.listRequestedApprovals(
                new GovernanceApplicationApi.RequesterApprovalsQuery(
                        command.tenantId(), command.ownerId(), "WORKSPACE", workspace.id(), null, 200));
        if (approvals.stream().anyMatch(value ->
                value.state() == com.spaceagent.platform.governance.domain.ApprovalState.PENDING)) {
            throw business("PROJECT_PLAN_EXECUTION_APPROVAL_PENDING", HttpStatus.CONFLICT);
        }
        if (job.pendingApprovalId() != null && approvals.stream().noneMatch(value ->
                job.pendingApprovalId().equals(value.id())
                        && value.state() == com.spaceagent.platform.governance.domain.ApprovalState.APPROVED)) {
            throw business("PROJECT_PLAN_EXECUTION_APPROVAL_REQUIRED", HttpStatus.CONFLICT);
        }
    }

    private static com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.TransitionCommand
            resumeTransition(
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ResumeCommand command) {
        return new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.TransitionCommand(
                command.tenantId(), command.ownerId(), command.projectId(),
                command.taskPlanId(), command.executionId());
    }

    private static com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.TransitionCommand
            cancelTransition(
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.CancelCommand command) {
        return new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.TransitionCommand(
                command.tenantId(), command.ownerId(), command.projectId(),
                command.taskPlanId(), command.executionId());
    }

    private static com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.TransitionCommand
            controlTransition(
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView execution) {
        return new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.TransitionCommand(
                execution.tenantId(), execution.ownerId(), execution.projectId(),
                execution.taskPlanId(), execution.id());
    }

    private DispatchView dispatchNext(
            DispatchCommand command, TaskPlanView plan,
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView planExecution) {
        String executionId = planExecution == null ? null : planExecution.id();
        if (planExecution != null && planExecution.state()
                != com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.RUNNING) {
            return new DispatchView(executionId, plan.id(), plan.status().name(), null, false);
        }
        if (plan.status() == TaskPlanStatus.COMPLETED) {
            if (planExecution != null) {
                planExecutions.complete(executionTransition(command, executionId));
            }
            return new DispatchView(executionId, plan.id(), plan.status().name(), null, false);
        }
        var inProgress = plan.steps().stream()
                .filter(step -> step.state() == PlanStepState.IN_PROGRESS)
                .findFirst().orElse(null);
        if (inProgress != null && waveConcurrency == null) {
            var active = activeJob(command, inProgress.id());
            if (active == null) {
                throw business("PROJECT_PLAN_ACTIVE_JOB_MISSING", HttpStatus.CONFLICT);
            }
            return new DispatchView(executionId, plan.id(), plan.status().name(), active, false);
        }
        List<com.spaceagent.platform.project.api.PlanStepView> readySteps;
        if(waveConcurrency==null){Set<String> completed=plan.steps().stream().filter(step->step.state()==PlanStepState.COMPLETED).map(com.spaceagent.platform.project.api.PlanStepView::id).collect(java.util.stream.Collectors.toSet());readySteps=plan.steps().stream().filter(step->step.state()==PlanStepState.PENDING||step.state()==PlanStepState.READY).filter(step->completed.containsAll(step.dependencyStepIds())).sorted(Comparator.comparingInt(com.spaceagent.platform.project.api.PlanStepView::sequence)).limit(1).toList();}
        else{ensureWaveBudget(planExecution,plan);var wave=ProjectPlanReadyWave.select(plan.steps().stream().map(step->new ProjectPlanReadyWave.Step(step.id(),step.sequence(),step.dependencyStepIds(),ProjectPlanReadyWave.StepState.valueOf(step.state().name()))).toList(),4);readySteps=wave.readyStepIds().stream().map(id->plan.steps().stream().filter(step->step.id().equals(id)).findFirst().orElseThrow()).toList();}
        if (readySteps.isEmpty()) {
            return new DispatchView(executionId, plan.id(), plan.status().name(), null, false);
        }
        ProjectCodingJobApplicationApi.JobView first=null;boolean createdAny=false;
        for(var ready:readySteps){
        String agentId = ready.preferredAgentId() == null
                ? command.agentId() : ready.preferredAgentId();
        if (!agentId.equals(command.agentId())) {
            throw business("PROJECT_PLAN_ASSIGNMENT_REQUIRED", HttpStatus.CONFLICT);
        }
        var existing = activeJob(command, ready.id());
        if (existing != null) {
            if(first==null)first=existing;continue;
        }
        com.spaceagent.platform.runtime.domain.ProjectPlanWaveClaim waveClaim=null;
        if(waveConcurrency!=null){var now=waveTime.now();waveClaim=waveConcurrency.claim(executionId,command.tenantId(),command.ownerId(),command.taskPlanId(),ready.id(),"project-plan-dispatch",java.util.UUID.randomUUID().toString(),now,now.plusSeconds(300)).orElse(null);if(waveClaim==null)continue;}
        var assignment = assignmentForDispatch(command, ready.id(), agentId);
        try{var created = enqueue(new ProjectCodingJobApplicationApi.EnqueueCommand(
                command.tenantId(), command.ownerId(), command.projectId(),
                command.projectDirectoryId(), command.conversationId(),
                command.sourceRepositoryId(), command.rootTaskId(), ready.childTaskId(),
                command.taskPlanId(), executionId, ready.id(), assignment.agentId(), assignment.primaryConfigurationHash(),
                assignment.reviewerConfigurationHash(), command.baseRef(),
                "project-plan-auto:" + command.taskPlanId() + ":" + ready.id()
                        + ":" + assignment.assignmentHash(), assignment.reviewerAgentId()));if(waveClaim!=null&&!waveConcurrency.bindJob(waveClaim,created.id(),waveTime.now()))throw business("PROJECT_PLAN_WAVE_BIND_FAILED",HttpStatus.CONFLICT);if(first==null)first=created;createdAny=true;}catch(RuntimeException error){if(waveClaim!=null)waveConcurrency.release(waveClaim,waveTime.now());throw error;}
        }
        return new DispatchView(executionId,plan.id(),plan.status().name(),first,createdAny);
    }

    private void ensureWaveBudget(com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView execution,TaskPlanView plan){if(waveConcurrency.findByExecutionId(execution.id()).isPresent())return;var now=waveTime.now();try{waveConcurrency.insert(new ProjectPlanWaveConcurrency(execution.id(),execution.tenantId(),execution.ownerId(),execution.projectId(),plan.id(),4,0,1,now,now));}catch(RuntimeException race){if(waveConcurrency.findByExecutionId(execution.id()).isEmpty())throw race;}}

    private ProjectPlanStepAssignmentApplicationApi.AssignmentView assignmentForDispatch(
            DispatchCommand command, String stepId, String agentId) {
        if (assignments == null) return new ProjectPlanStepAssignmentApplicationApi.AssignmentView(
                "compatibility", command.taskPlanId(), stepId, 0, null, agentId,
                null, command.reviewerAgentId(), null, null, null, null,
                "compatibility", null);
        try {
            var existing = assignments.get(new ProjectPlanStepAssignmentApplicationApi.AssignmentQuery(
                    command.tenantId(), command.ownerId(), command.projectId(), command.taskPlanId(), stepId));
            if (existing != null) return existing;
            return compatibilityAssignment(command, stepId, agentId);
        } catch (java.util.NoSuchElementException missing) {
            var primary = currentConfigurations.requireCurrent(
                    command.tenantId(), command.ownerId(), agentId);
            String reviewerAgentId = command.reviewerAgentId();
            if (reviewerAgentId == null) throw business("ASSIGNMENT_REQUIRED",HttpStatus.CONFLICT);
            return assignments.definePlanDefault(
                    new ProjectPlanStepAssignmentApplicationApi.PlanDefaultAssignmentCommand(
                            command.tenantId(), command.ownerId(), command.projectId(), command.taskPlanId(), stepId,
                            agentId, null, reviewerAgentId, null,
                            primary.modelPoolId(), null, null));
        }
    }

    private ProjectPlanStepAssignmentApplicationApi.AssignmentView compatibilityAssignment(
            DispatchCommand command, String stepId, String agentId) {
        return new ProjectPlanStepAssignmentApplicationApi.AssignmentView(
                "compatibility", command.taskPlanId(), stepId, 0, null, agentId,
                null, command.reviewerAgentId(), null, null, null, null,
                "compatibility", null);
    }

    private ProjectCodingJobApplicationApi.JobView activeJob(
            DispatchCommand command, String stepId) {
        return jobs.list(new ProjectCodingJobApplicationApi.ListQuery(
                        command.tenantId(), command.ownerId(), command.projectId(),
                        command.taskPlanId(), stepId, 1, 100)).items().stream()
                .filter(value -> Set.of(
                        ProjectCodingJobState.PENDING,
                        ProjectCodingJobState.RUNNING,
                        ProjectCodingJobState.WAITING_APPROVAL,
                        ProjectCodingJobState.BLOCKED).contains(value.state()))
                .max(Comparator.comparing(ProjectCodingJobApplicationApi.JobView::updatedAt))
                .orElse(null);
    }

    private void dispatchSuccessor(
            ProjectCodingJobApplicationApi.JobView job,
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView planExecution) {
        var plan = plans.getPlan(new GetTaskPlanQuery(
                job.tenantId(), job.ownerId(), job.projectId(),
                job.rootTaskId(), job.taskPlanId()));
        dispatchNext(new DispatchCommand(
                job.tenantId(), job.ownerId(), job.projectId(), job.rootTaskId(),
                job.taskPlanId(), job.projectDirectoryId(), job.conversationId(),
                job.sourceRepositoryId(), job.agentId(), job.primaryConfigurationHash(),
                job.reviewerConfigurationHash(), job.baseRef(), job.reviewerAgentId()), plan, planExecution);
    }

    public boolean cancelIfRequested(
            ProjectCodingJobApplicationApi.JobView job,
            ProjectCodingJobApplicationApi.ClaimCommand claim) {
        if (job.executionId() == null) return false;
        var execution = planExecutions.get(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.Query(
                        job.tenantId(), job.ownerId(), job.projectId(), job.executionId()));
        if (execution == null || (execution.state()
                != com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.CANCELLING
                && execution.state()
                != com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.CANCELLED)) {
            return false;
        }
        if (execution.state()
                == com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.CANCELLED) {
            releaseWave(job, claim);
            return true;
        }
        String blocker = cancellationBlocker(job);
        if (blocker != null) {
            planExecutions.block(controlTransition(execution), blocker);
            jobs.fail(new ProjectCodingJobApplicationApi.FailCommand(
                    job.id(), claim.workerId(), claim.claimToken(), claim.fencingToken(),
                    blocker, true));
            releaseWave(job, claim);
            return true;
        }
        if (!cancelRunFenced(job, "project-plan-cancel:" + claim.workerId())) {
            jobs.releaseForPause(claim);
            releaseWave(job, claim);
            return true;
        }
        jobs.fail(new ProjectCodingJobApplicationApi.FailCommand(
                job.id(), claim.workerId(), claim.claimToken(), claim.fencingToken(),
                "PROJECT_PLAN_EXECUTION_CANCELLED", false));
        releaseWave(job, claim);
        cancelTaskPlan(execution);
        planExecutions.acknowledgeCancel(controlTransition(execution));
        return true;
    }

    private String cancellationBlocker(ProjectCodingJobApplicationApi.JobView job) {
        if (job.sourceMergeId() != null) {
            return "PROJECT_PLAN_EXECUTION_MERGE_PROVEN";
        }
        if (job.codingRunId() == null) return null;
        boolean unknownModel = modelCalls.findByRunId(job.codingRunId()).stream()
                .anyMatch(value -> value.status()
                        == com.spaceagent.platform.inference.domain.ModelCallStatus.UNKNOWN);
        boolean unknownTool = toolLedger.findByRunId(job.codingRunId()).stream()
                .anyMatch(value -> value.status()
                        == com.spaceagent.platform.tooling.domain.ToolExecutionStatus.UNKNOWN);
        return unknownModel || unknownTool ? "PROJECT_PLAN_EXECUTION_UNKNOWN_EFFECT" : null;
    }

    private boolean cancelRunFenced(
            ProjectCodingJobApplicationApi.JobView job, String leaseOwner) {
        if (job.codingRunId() == null) return true;
        var run = runtime.findRun(job.codingRunId()).orElse(null);
        if (run == null) return false;
        if (Set.of(AgentRunState.COMPLETED, AgentRunState.FAILED,
                AgentRunState.CANCELLED).contains(run.state())) return true;
        var claim = coordination.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), leaseOwner, 60));
        if (claim.type()
                != com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaimType.ACQUIRED) {
            return false;
        }
        var lease = claim.lease();
        try {
            runtime.cancelFenced(new CancelAgentRunFencedCommand(
                    run.id(), lease.leaseToken(), lease.fencingToken(),
                    "project-plan-execution-cancelled"));
            return true;
        } finally {
            try {
                coordination.releaseLease(new RuntimeCoordinationApplicationApi.ReleaseLeaseCommand(
                        run.id(), leaseOwner, lease.leaseToken(), lease.fencingToken()));
            } catch (RuntimeException ignored) {
                // Terminal Run state remains authoritative if lease release races another replica.
            }
        }
    }

    private void cancelProjectStep(ProjectCodingJobApplicationApi.JobView job) {
        var reference = execution.resolve(
                new com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery(
                        job.tenantId(), job.ownerId(), job.projectId(), job.taskId(),
                        job.taskPlanId(), job.planStepId()));
        if (!Set.of(PlanStepState.COMPLETED, PlanStepState.FAILED,
                PlanStepState.CANCELLED).contains(reference.planStepState())) {
            execution.transition(new TransitionPlanStepExecutionCommand(
                    job.tenantId(), job.ownerId(), job.projectId(), job.taskId(),
                    job.taskPlanId(), job.planStepId(), PlanStepExecutionAction.CANCEL));
        }
    }

    private void cancelTaskPlan(
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView executionView) {
        TaskPlanView plan = plans.getPlan(new GetTaskPlanQuery(
                executionView.tenantId(), executionView.ownerId(), executionView.projectId(),
                executionView.rootTaskId(), executionView.taskPlanId()));
        if (!plan.status().isTerminal()) {
            plans.transition(new TaskPlanActionCommand(
                    executionView.tenantId(), executionView.ownerId(), executionView.projectId(),
                    executionView.rootTaskId(), executionView.taskPlanId(), TaskPlanAction.CANCEL));
        }
    }

    public boolean pauseIfRequested(
            ProjectCodingJobApplicationApi.JobView job,
            ProjectCodingJobApplicationApi.ClaimCommand claim) {
        if (job.executionId() == null || job.codingRunId() == null) return false;
        var planExecution = planExecutions.get(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.Query(
                        job.tenantId(), job.ownerId(), job.projectId(), job.executionId()));
        if (planExecution == null || (planExecution.state()
                != com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSING
                && planExecution.state()
                != com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSED)) {
            return false;
        }
        if (planExecution.state()
                == com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.PAUSING) {
            runtime.createCheckpoint(new com.spaceagent.platform.runtime.api.CreateCheckpointCommand(
                    job.codingRunId(), write(Map.of(
                            "phase", "project-plan-paused",
                            "executionId", job.executionId(),
                            "jobId", job.id(),
                            "taskPlanId", job.taskPlanId(),
                            "planStepId", job.planStepId(),
                            "jobRevision", job.revision()))));
            jobs.releaseForPause(claim);
            releaseWave(job, claim);
            planExecutions.acknowledgePause(
                    new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi
                            .TransitionCommand(
                            job.tenantId(), job.ownerId(), job.projectId(), job.taskPlanId(),
                            job.executionId()));
        }
        return true;
    }

    private com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView
            lockExecution(ProjectCodingJobApplicationApi.JobView job) {
        if (job.executionId() == null) return null;
        return planExecutions.begin(new com.spaceagent.platform.runtime.api
                .ProjectPlanExecutionApplicationApi.TransitionCommand(
                job.tenantId(), job.ownerId(), job.projectId(), job.taskPlanId(),
                job.executionId()));
    }

    private static com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.StartCommand
            executionStart(DispatchCommand command) {
        return new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.StartCommand(
                stableExecutionId(command.taskPlanId()), command.tenantId(), command.ownerId(),
                command.projectId(), command.projectDirectoryId(), command.conversationId(),
                command.sourceRepositoryId(), command.rootTaskId(), command.taskPlanId(),
                command.agentId(), command.primaryConfigurationHash(), command.reviewerAgentId(),
                command.baseRef(), "project-plan-execution:" + command.taskPlanId());
    }

    private static com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.TransitionCommand
            executionTransition(DispatchCommand command, String executionId) {
        return new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.TransitionCommand(
                command.tenantId(), command.ownerId(), command.projectId(),
                command.taskPlanId(), executionId);
    }

    private static String stableExecutionId(String taskPlanId) {
        return java.util.UUID.nameUUIDFromBytes(
                ("project-plan-execution:" + taskPlanId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    public ProjectCodingJobApplicationApi.JobView get(ProjectCodingJobApplicationApi.Query query) {
        ProjectCodingJobApplicationApi.JobView job = jobs.get(query);
        directories.get(new ProjectDirectoryApplicationApi.Query(
                query.tenantId(), query.ownerId(), query.projectId(), job.projectDirectoryId()));
        return job;
    }

    public ProjectCodingJobApplicationApi.JobPage list(ProjectCodingJobApplicationApi.ListQuery query) {
        directories.list(new ProjectDirectoryApplicationApi.ListQuery(
                query.tenantId(), query.ownerId(), query.projectId()));
        return jobs.list(query);
    }

    public ProjectCodingJobApplicationApi.JobView resume(ProjectCodingJobApplicationApi.ResumeCommand command) {
        ProjectCodingJobApplicationApi.JobView job = jobs.get(new ProjectCodingJobApplicationApi.Query(
                command.tenantId(), command.ownerId(), command.projectId(), command.taskPlanId(),
                command.planStepId(), command.jobId()));
        directories.get(new ProjectDirectoryApplicationApi.Query(
                command.tenantId(), command.ownerId(), command.projectId(), job.projectDirectoryId()));
        return jobs.resume(command);
    }

    public boolean runOnce(String worker,int lease,int maximumAttempts,int maximumIterations,int maximumReviews){
        var claim=jobs.claim(worker,lease,maximumAttempts).orElse(null);if(claim==null)return false;
        var initialCommand=command(claim.job(),worker,claim);
        if(!ensureWaveLease(claim.job(),initialCommand,claim.leaseUntil())){
            jobs.releaseForPause(initialCommand);
            return true;
        }
        long revision=currentConfigurations==null?0:currentConfigurations.requireCurrent(claim.job().tenantId(),claim.job().ownerId(),claim.job().agentId()).agentRevision();
        try(RuntimeOperationalTelemetry.InvocationSpan span=telemetry.startAgent(Long.toString(revision),"spaceagent.project_coding")){
            execute(claim,worker,lease,Math.max(1,Math.min(50,maximumIterations)),Math.max(1,Math.min(5,maximumReviews)));
            var after=jobs.get(new ProjectCodingJobApplicationApi.Query(claim.job().tenantId(),claim.job().ownerId(),claim.job().projectId(),claim.job().taskPlanId(),claim.job().planStepId(),claim.job().id()));
            if(after.state()==ProjectCodingJobState.COMPLETED)span.success();
            else if(after.state()==ProjectCodingJobState.FAILED||after.state()==ProjectCodingJobState.BLOCKED)span.error(after.safeErrorCode());
            return true;
        }
    }

    private void execute(ProjectCodingJobApplicationApi.ClaimView lease,String worker,int leaseSeconds,int maxIterations,int maxReviews){
        var job=lease.job();var claim=command(job,worker,lease);
        try{
            validateActiveJob(job);
            startPlanStep(job);
            job=workspace(job,claim);claim=command(job,worker,lease);
            job=run(job,claim);claim=command(job,worker,lease);
            if (cancelIfRequested(job, claim)) return;
            if (pauseIfRequested(job, claim)) return;
            CodingContext context=lease.contextJson()==null?context(job):readContext(lease.contextJson());
            if(lease.contextJson()==null){job=jobs.initializeContext(claim,write(context));claim=command(job,worker,lease);}
            AgentExecutionConfigurationView agent=runConfiguration(job.tenantId(),job.ownerId(),job.codingRunId(),job.primaryConfigurationHash());
            int max=Math.min(maxIterations,agent.maxTurns());
            PendingTool pending=lease.pendingToolJson()==null?null:readPending(lease.pendingToolJson());
            while(job.iteration()<max){
                job=jobs.heartbeat(claim,leaseSeconds);claim=command(job,worker,lease);renewWaveLease(job,claim,leaseSeconds);
                if (cancelIfRequested(job, claim)) return;
                if (pauseIfRequested(job, claim)) return;
                if(pending!=null){ToolOutcome outcome=tool(job,pending,claim);if(outcome.waiting())return;
                    context=append(context,"user","Tool "+pending.name()+" returned "+outcome.status()+":\n"+bound(outcome.result(),120_000));
                    job=jobs.recordToolResult(claim,write(context),job.iteration()+1);claim=command(job,worker,lease);pending=null;continue;}
                var model=model(job,agent,context);if(model.toolCalls().size()>1)throw business("PROJECT_CODING_PARALLEL_WRITE_REJECTED",HttpStatus.BAD_GATEWAY);
                if(model.toolCalls().size()==1){var call=model.toolCalls().getFirst();if(!CODING_TOOLS.contains(call.name()))throw business("PROJECT_CODING_TOOL_INVALID",HttpStatus.BAD_GATEWAY);
                    pending=new PendingTool(call.id(),call.name(),call.arguments());job=jobs.savePendingTool(claim,write(pending));claim=command(job,worker,lease);continue;}
                if (cancelIfRequested(job, claim)) return;
                if (pauseIfRequested(job, claim)) return;
                Completion completion=completion(model.content());
                var step=planStep(job);if(!accepted(step.acceptanceCriteria(),completion.acceptance())){
                    context=append(context,"user","Completion was rejected: every exact acceptance criterion must PASS with evidence.");
                    job=jobs.recordToolResult(claim,write(context),job.iteration()+1);claim=command(job,worker,lease);continue;}
                requirePassingTest(job.codingRunId());
                for(Acceptance value:completion.acceptance())coding.evidence(new CodingRuntimeApplicationApi.EvidenceCommand(job.ownerId(),job.codingRunId(),job.workspaceId(),value.criterion(),value.passed(),bound(value.details(),4000)));
                var prepared=coding.prepareCompletion(new CodingRuntimeApplicationApi.FinalizeCommand(job.ownerId(),job.codingRunId(),job.workspaceId(),completion.commitMessage()));
                var selected=selectedArtifacts(job.codingRunId());
                job=jobs.recordPrepared(claim,selected.patch().id(),selected.commit().id());claim=command(job,worker,lease);
                ReviewResult review=review(job,selected,claim);
                CodingContext nextContext=review.approved()?context:append(context,"user",
                        "Reviewer requested changes:\n"+bound(review.evidence(),8000));
                job=jobs.recordReview(claim,review.reviewId(),review.approved(),
                        review.approved()?null:write(nextContext),job.reviewRound()+1);
                claim=command(job,worker,lease);
                if(!review.approved()){
                    if(job.reviewRound()>=maxReviews)throw business("PROJECT_CODING_REVIEW_LIMIT",HttpStatus.CONFLICT);
                    context=nextContext;
                    continue;
                }
                if (cancelIfRequested(job, claim)) return;
                if (pauseIfRequested(job, claim)) return;
                var merge=prepareMerge(job,review.reviewId(),selected.commit().id());
                if(merge.state()!=com.spaceagent.platform.project.domain.SourceMergeState.READY)
                    throw business("PROJECT_CODING_MERGE_UNKNOWN",HttpStatus.CONFLICT);
                ProjectCodingJobApplicationApi.JobView completedJob=job;
                ProjectCodingJobApplicationApi.ClaimCommand completedClaim=claim;
                var controlChange = completeReviewedJob(
                        completedJob, completedClaim, merge.id());
                if (controlChange != null) {
                    handleControlChange(completedJob, completedClaim, controlChange);
                    return;
                }
                if (mergeBarriers == null) {
                    String completedJobId=job.id();
                    projectHandoffs.findByTargetCodingJobId(completedJobId)
                            .ifPresent(value -> projectHandoffs.readyForFinalization(completedJobId));
                }
                return;
            }
            throw business("PROJECT_CODING_ITERATION_LIMIT",HttpStatus.CONFLICT);
        } catch(RuntimeException error){fail(job,claim,error);}
    }

    com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView
            completeReviewedJob(
            ProjectCodingJobApplicationApi.JobView completedJob,
            ProjectCodingJobApplicationApi.ClaimCommand completedClaim,
            String sourceMergeId) {
        Integer applyIndex = mergeBarriers == null ? null : plans.getPlan(new GetTaskPlanQuery(
                        completedJob.tenantId(), completedJob.ownerId(), completedJob.projectId(),
                        completedJob.rootTaskId(), completedJob.taskPlanId())).steps().stream()
                .filter(value -> value.id().equals(completedJob.planStepId()))
                .findFirst().orElseThrow().sequence();
        var control=transactions.execute(status -> {
            var lockedExecution = lockExecution(completedJob);
            if (lockedExecution != null && lockedExecution.state()
                    != com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState.RUNNING) {
                return lockedExecution;
            }
            if (applyIndex == null) {
                jobs.complete(completedClaim, sourceMergeId);
            } else {
                jobs.completeAndEnqueueBarrier(
                        new ProjectCodingJobApplicationApi.BarrierCompletionCommand(
                                completedClaim, sourceMergeId, applyIndex));
            }
            releaseWave(completedJob, completedClaim);
            return null;
        });
        if(control!=null)return control;
        if(mergeBarriers==null){
            completeAppliedStep(completedJob);
            dispatchSuccessor(completedJob,planExecutions.get(new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.Query(completedJob.tenantId(),completedJob.ownerId(),completedJob.projectId(),completedJob.executionId())));
        }
        return null;
    }

    public boolean drainMergeBarrierOnce(String workerId, int leaseSeconds, int maximumAttempts) {
        if (mergeBarriers == null) return false;
        var claimed = mergeBarriers.claimNext(workerId, leaseSeconds, maximumAttempts);
        if (claimed.isEmpty()) return false;
        drainMergeBarrierClaim(claimed.orElseThrow(), leaseSeconds);
        return true;
    }

    private void drainMergeBarrierClaim(
            ProjectPlanMergeBarrierApplicationApi.ClaimView originalClaim, int leaseSeconds) {
        var outcome = resolveMergeOutcome(originalClaim);
        if (outcome == null) {
            mergeBarriers.blockUnknown(barrierCommand(originalClaim),
                    "PROJECT_MERGE_OUTCOME_UNKNOWN");
            return;
        }
        var claim = mergeBarriers.heartbeat(barrierCommand(originalClaim), leaseSeconds);
        switch (outcome.state()) {
            case READY -> mergeBarriers.releaseKnownNoEffect(barrierCommand(claim));
            case APPLIED_LOCAL -> finalizeAppliedBarrier(claim);
            case CONFLICT -> blockConflictBarrier(claim, outcome);
            case APPLYING, UNKNOWN -> mergeBarriers.blockUnknown(
                    barrierCommand(claim), "PROJECT_MERGE_OUTCOME_UNKNOWN");
            default -> mergeBarriers.blockUnknown(
                    barrierCommand(claim), "PROJECT_MERGE_STATE_INVALID");
        }
    }

    private SourceMergeApplicationApi.SourceMergeView resolveMergeOutcome(
            ProjectPlanMergeBarrierApplicationApi.ClaimView claim) {
        try {
            var current = merges.get(new ReviewedSourceMergeApplicationApi.Query(
                    claim.tenantId(), claim.ownerId(), claim.projectId(), claim.sourceMergeId()));
            return switch (current.state()) {
                case READY -> merges.apply(new ReviewedSourceMergeApplicationApi.ApplyCommand(
                        claim.tenantId(), claim.ownerId(), claim.projectId(), claim.sourceMergeId(),
                        jobForBarrier(claim).pendingApprovalId()));
                case APPLYING, UNKNOWN -> merges.reconcile(new ReviewedSourceMergeApplicationApi.Query(
                        claim.tenantId(), claim.ownerId(), claim.projectId(), claim.sourceMergeId()));
                default -> current;
            };
        } catch (RuntimeException firstFailure) {
            try {
                var current = merges.get(new ReviewedSourceMergeApplicationApi.Query(
                        claim.tenantId(), claim.ownerId(), claim.projectId(), claim.sourceMergeId()));
                if (java.util.Set.of(SourceMergeState.APPLYING, SourceMergeState.UNKNOWN,
                        SourceMergeState.CONFLICT).contains(current.state())) {
                    return merges.reconcile(new ReviewedSourceMergeApplicationApi.Query(
                            claim.tenantId(), claim.ownerId(), claim.projectId(), claim.sourceMergeId()));
                }
                return current;
            } catch (RuntimeException recoveryFailure) {
                return null;
            }
        }
    }

    private void finalizeAppliedBarrier(ProjectPlanMergeBarrierApplicationApi.ClaimView claim) {
        var affected = jobForBarrier(claim);
        completeAppliedStep(affected);
        projectHandoffs.findByTargetCodingJobId(affected.id())
                .ifPresent(value -> projectHandoffs.readyForFinalization(affected.id()));
        dispatchSuccessor(affected, planExecutions.get(
                new com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.Query(
                        affected.tenantId(), affected.ownerId(), affected.projectId(),
                        affected.executionId())));
        mergeBarriers.completeApplied(barrierCommand(claim));
    }

    private void blockConflictBarrier(
            ProjectPlanMergeBarrierApplicationApi.ClaimView claim,
            SourceMergeApplicationApi.SourceMergeView outcome) {
        var affected = jobForBarrier(claim);
        var evidence = selectedArtifacts(affected.codingRunId());
        reconciliation.onBaseDrift(new ProjectReconciliationIntegrationApi.BaseDriftCommand(
                affected.tenantId(), affected.ownerId(), affected.ownerId(), affected.projectId(),
                affected.projectDirectoryId(), affected.taskId(), affected.taskPlanId(),
                affected.planStepId(), affected.executionId(), affected.executionId(),
                claim.sourceMergeId(), affected.sourceRepositoryId(), outcome.expectedBaseCommit(),
                outcome.actualTargetCommit(), affected.patchArtifactId(), affected.commitArtifactId(),
                evidence.test().id(), affected.reviewId()));
        mergeBarriers.blockConflict(barrierCommand(claim));
    }

    private ProjectCodingJobApplicationApi.JobView jobForBarrier(
            ProjectPlanMergeBarrierApplicationApi.ClaimView claim) {
        return jobs.list(new ProjectCodingJobApplicationApi.ListQuery(
                        claim.tenantId(), claim.ownerId(), claim.projectId(), claim.taskPlanId(),
                        claim.planStepId(), 1, 100)).items().stream()
                .filter(value -> claim.sourceMergeId().equals(value.sourceMergeId()))
                .findFirst().orElseThrow(() -> business(
                        "PROJECT_MERGE_JOB_MISSING", HttpStatus.CONFLICT));
    }

    private static ProjectPlanMergeBarrierApplicationApi.ClaimCommand barrierCommand(
            ProjectPlanMergeBarrierApplicationApi.ClaimView claim) {
        return new ProjectPlanMergeBarrierApplicationApi.ClaimCommand(
                claim.executionId(), claim.tenantId(), claim.ownerId(), claim.projectId(),
                claim.taskPlanId(), claim.applyIndex(), claim.planStepId(), claim.sourceMergeId(),
                claim.barrierRevision(), claim.attempt(), claim.workerId(),
                claim.claimToken(), claim.fencingToken(), claim.leaseUntil());
    }

    private void completeAppliedStep(ProjectCodingJobApplicationApi.JobView job){transactions.executeWithoutResult(status->{var reference=execution.resolve(new com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery(job.tenantId(),job.ownerId(),job.projectId(),job.taskId(),job.taskPlanId(),job.planStepId()));if(reference.planStepState()!=PlanStepState.COMPLETED)execution.transition(new TransitionPlanStepExecutionCommand(job.tenantId(),job.ownerId(),job.projectId(),job.taskId(),job.taskPlanId(),job.planStepId(),PlanStepExecutionAction.COMPLETE));runtime.findRun(job.codingRunId()).filter(value->value.state()!=AgentRunState.COMPLETED).ifPresent(value->runtime.complete(new CompleteAgentRunCommand(value.id())));});}

    private void handleControlChange(
            ProjectCodingJobApplicationApi.JobView job,
            ProjectCodingJobApplicationApi.ClaimCommand claim,
            com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi.ExecutionView value) {
        switch (value.state()) {
            case PAUSING -> {
                if (!pauseIfRequested(job, claim)) releaseForPause(job, claim);
            }
            case PAUSED -> releaseForPause(job, claim);
            case CANCELLING -> {
                if (!cancelIfRequested(job, claim)) releaseForPause(job, claim);
            }
            case CANCELLED -> { jobs.fail(new ProjectCodingJobApplicationApi.FailCommand(
                    job.id(), claim.workerId(), claim.claimToken(), claim.fencingToken(),
                    "PROJECT_PLAN_EXECUTION_CANCELLED", false)); releaseWave(job,claim); }
            case BLOCKED -> { jobs.fail(new ProjectCodingJobApplicationApi.FailCommand(
                    job.id(), claim.workerId(), claim.claimToken(), claim.fencingToken(),
                    value.safeErrorCode() == null
                            ? "PROJECT_PLAN_EXECUTION_BLOCKED" : value.safeErrorCode(), true)); releaseWave(job,claim); }
            default -> releaseForPause(job, claim);
        }
    }

    private ProjectCodingJobApplicationApi.JobView workspace(ProjectCodingJobApplicationApi.JobView job,ProjectCodingJobApplicationApi.ClaimCommand claim){
        if(job.workspaceId()!=null)return job;String isolation=job.executionId()==null
                ? "plan-step:"+job.planStepId()
                : "execution:"+job.executionId()+":plan-step:"+job.planStepId();
        var existing=workspaces.list(new WorkspaceApplicationApi.ListQuery(job.tenantId(),job.ownerId(),job.projectId())).stream()
                .filter(v->v.taskId().equals(job.taskId())&&v.sourceRepositoryId().equals(job.sourceRepositoryId()))
                .filter(v->isolation.equals(v.isolationKey())&&v.state()==WorkspaceState.READY&&v.mode()==WorkspaceMode.MANAGED_GIT).findFirst().orElse(null);
        var workspace=existing==null?workspaces.provision(new WorkspaceApplicationApi.ProvisionCommand(job.tenantId(),job.ownerId(),job.projectId(),job.projectDirectoryId(),job.taskId(),job.sourceRepositoryId(),job.baseRef(),isolation)):existing;
        return jobs.attachWorkspace(claim,workspace.id());}
    private void validateActiveJob(ProjectCodingJobApplicationApi.JobView job){directories.get(new ProjectDirectoryApplicationApi.Query(job.tenantId(),job.ownerId(),job.projectId(),job.projectDirectoryId()));sources.get(new GetSourceRepositoryQuery(job.tenantId(),job.ownerId(),job.projectId(),job.sourceRepositoryId()));conversations.find(job.conversationId()).filter(v->v.tenantId().equals(job.tenantId())&&v.userId().equals(job.ownerId())&&job.projectId().equals(v.projectId())&&job.projectDirectoryId().equals(v.projectDirectoryId())&&v.status()==ConversationStatus.ACTIVE).orElseThrow(()->business("PROJECT_CODING_CONVERSATION_INVALID",HttpStatus.NOT_FOUND));var reference=execution.resolve(new com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery(job.tenantId(),job.ownerId(),job.projectId(),job.taskId(),job.taskPlanId(),job.planStepId()));if(!Set.of(PlanStepState.PENDING,PlanStepState.READY,PlanStepState.IN_PROGRESS).contains(reference.planStepState()))throw business("PROJECT_CODING_STEP_INVALID",HttpStatus.CONFLICT);validateCurrent(job.tenantId(),job.ownerId(),job.agentId());}
    private void startPlanStep(ProjectCodingJobApplicationApi.JobView job) {
        var reference = execution.resolve(
                new com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery(
                        job.tenantId(), job.ownerId(), job.projectId(), job.taskId(),
                        job.taskPlanId(), job.planStepId()));
        if (reference.planStepState() == PlanStepState.IN_PROGRESS) return;
        execution.transition(new TransitionPlanStepExecutionCommand(
                job.tenantId(), job.ownerId(), job.projectId(), job.taskId(),
                job.taskPlanId(), job.planStepId(), PlanStepExecutionAction.START));
    }
    private ProjectCodingJobApplicationApi.JobView run(ProjectCodingJobApplicationApi.JobView job,ProjectCodingJobApplicationApi.ClaimCommand claim){
        if(job.codingRunId()!=null){projectHandoffs.findByTargetCodingJobId(job.id()).ifPresent(value->projectHandoffs.attachTargetRun(job.id(),job.codingRunId()));return job;}
        var attached=transactions.execute(s->{var run=coding.start(new CodingRuntimeApplicationApi.StartCommand(job.tenantId(),job.ownerId(),job.agentId(),null,job.conversationId(),job.projectId(),job.taskId(),job.taskPlanId(),job.planStepId(),job.workspaceId()));var result=jobs.attachCodingRun(claim,run.agentRunId());projectHandoffs.findByTargetCodingJobId(job.id()).ifPresent(value->projectHandoffs.attachTargetRun(job.id(),run.agentRunId()));return result;});
        return attached;
    }
    private CodingContext context(ProjectCodingJobApplicationApi.JobView job){
        var task=tasks.getTask(new GetTaskQuery(job.tenantId(),job.ownerId(),job.projectId(),job.taskId()));
        var step=planStep(job);
        var blueprint=blueprints.list(new ProjectBlueprintApplicationApi.ListQuery(job.tenantId(),job.ownerId(),job.projectId())).stream().filter(v->v.status()==ProjectBlueprintStatus.CONFIRMED).max(Comparator.comparingInt(ProjectBlueprintApplicationApi.BlueprintView::versionNumber)).orElse(null);
        String system="You are an autonomous coding agent. Work only through the provided tools. Inspect before editing, run relevant tests, never claim success without a successful command result, and return strict completion JSON when ready: {\"summary\":\"\",\"commitMessage\":\"\",\"acceptance\":[{\"criterion\":\"exact criterion\",\"passed\":true,\"details\":\"evidence\"}]}. One tool call per turn.";
        String evidence="Workspace ID for all file/coding tools: "+job.workspaceId()+"\nSelected source branch: "+job.baseRef()+"\nTask: "+task.goal()+"\nExpected output: "+step.expectedOutput()+"\nCriteria: "+step.acceptanceCriteria()+"\nBlueprint: "+(blueprint==null?"missing":write(blueprint.document()));
        var projectMemory=memory.recall(new MemoryRecallCommand(MemoryScopeRef.project(job.projectId()),List.of(),30));
        if(!projectMemory.isEmpty())evidence+="\nProject memory: "+bound(write(projectMemory),40_000);
        var handoff=projectHandoffs.findByTargetCodingJobId(job.id()).orElse(null);
        if(handoff!=null){var snapshot=recovery.get(new ProjectRecoveryApplicationApi.Query(job.tenantId(),job.ownerId(),job.projectId(),handoff.sourceAgentRunId(),handoff.recoverySnapshotId()));if(!snapshot.context().unknownToolEffects().isEmpty()||!snapshot.context().unknownModelEffects().isEmpty())throw business("PROJECT_HANDOFF_UNKNOWN_RECONCILIATION_REQUIRED",HttpStatus.CONFLICT);evidence+="\nAuthoritative handoff recovery package: "+bound(write(snapshot),100_000);}
        return new CodingContext(List.of(new InferenceExecutionApi.InferenceMessage("system",system),new InferenceExecutionApi.InferenceMessage("user",bound(evidence,180_000))));
    }
    private com.spaceagent.platform.project.api.PlanStepView planStep(ProjectCodingJobApplicationApi.JobView job){return plans.getPlan(new GetTaskPlanQuery(job.tenantId(),job.ownerId(),job.projectId(),job.rootTaskId(),job.taskPlanId())).steps().stream().filter(v->v.id().equals(job.planStepId())).findFirst().orElseThrow();}
    private InferenceExecutionApi.InferenceExecutionResult model(
            ProjectCodingJobApplicationApi.JobView job,
            AgentExecutionConfigurationView agent,
            CodingContext context) {
        var step = runtime.startStep(new StartRunStepCommand(
                job.codingRunId(), "project-coding-model:" + job.iteration()));
        try {
            List<String> enabled = catalog.resolveTools(agent.enabledToolIds()).stream()
                    .map(RuntimeCapabilityCatalogApplicationApi.CapabilityView::id)
                    .filter(CODING_TOOLS::contains)
                    .toList();
            if (enabled.isEmpty()) {
                throw business("PROJECT_CODING_TOOLS_NOT_ENABLED", HttpStatus.CONFLICT);
            }
            List<SkillCapabilityView> skills = catalog.resolvePinnedSkills(
                    job.tenantId(), agent.skillIds(), agent.enabledToolIds());
            checkpointSkillUse(job.codingRunId(), step.id(), skills);
            CodingContext effectiveContext = withSkills(context, skills);
            var selection = selection(job.tenantId(), job.ownerId(), job.id(), agent);
            var definitions = catalog.resolveTools(enabled).stream()
                    .map(value -> Map.<String, Object>of(
                            "name", value.id(), "description", value.description(),
                            "parameters", value.inputSchema()))
                    .toList();
            var result = inference.execute(new InferenceExecutionApi.InferenceExecutionCommand(
                    selection.provider(), selection.model(), effectiveContext.messages(),
                    Map.of("temperature", agent.temperature(),
                            "maxOutputTokens", agent.maxOutputTokens(),
                            "enabledToolIds", enabled, "toolDefinitions", definitions),
                    job.codingRunId(), step.id(),
                    "project-coding:" + job.id() + ":round:" + job.iteration(),
                    job.tenantId(), selection.pool(), selection.candidates(), selection.strategy(),
                    selection.snapshot(), selection.fallback()));
            runtime.completeStep(new CompleteRunStepCommand(job.codingRunId(), step.id()));
            return result;
        } catch (RuntimeException error) {
            runtime.failStep(new FailRunStepCommand(job.codingRunId(), step.id()));
            throw error;
        }
    }
    private ToolOutcome tool(ProjectCodingJobApplicationApi.JobView job,PendingTool pending,ProjectCodingJobApplicationApi.ClaimCommand claim){String arguments=pending.arguments();if(job.pendingApprovalId()!=null){try{ObjectNode node=(ObjectNode)json.readTree(arguments);node.put("approvalId",job.pendingApprovalId());arguments=json.writeValueAsString(node);}catch(Exception e){throw business("PROJECT_CODING_TOOL_INVALID",HttpStatus.BAD_REQUEST);}}var step=runtime.startStep(new StartRunStepCommand(job.codingRunId(),"tool:"+pending.name()));try{if(runtime.findRun(job.codingRunId()).orElseThrow().state()==AgentRunState.WAITING_FOR_USER)runtime.markRunInProgress(job.codingRunId());var result=tools.execute(new RuntimeToolExecutionApplicationApi.ExecuteRuntimeToolCommand(job.ownerId(),job.codingRunId(),step.id(),pending.id(),pending.name(),arguments));if("UNKNOWN".equals(result.status()))throw business("PROJECT_CODING_TOOL_UNKNOWN",HttpStatus.CONFLICT);if("SUCCEEDED".equals(result.status()))runtime.completeStep(new CompleteRunStepCommand(job.codingRunId(),step.id()));else runtime.failStep(new FailRunStepCommand(job.codingRunId(),step.id()));return new ToolOutcome(false,result.status(),result.result()==null?result.error():result.result());}catch(GovernanceApprovalRequiredException approval){runtime.failStep(new FailRunStepCommand(job.codingRunId(),step.id()));runtime.markRunWaitingForUser(job.codingRunId());jobs.waitForApproval(claim,approval.approvalId());return new ToolOutcome(true,"WAITING_APPROVAL",null);}}
    private ReviewResult review(
            ProjectCodingJobApplicationApi.JobView job,
            SelectedArtifacts selected,
            ProjectCodingJobApplicationApi.ClaimCommand claim) {
        String reviewerRun = job.reviewerRunId();
        if (reviewerRun == null) {
            reviewerRun = transactions.execute(status -> {
                var run = runtime.startRun(new StartAgentRunCommand(
                        job.tenantId(), job.ownerId(), job.reviewerAgentId(), null,
                        job.conversationId(), null, null, null, null, null, null));
                runtime.markRunInProgress(run.id());
                jobs.attachReviewerRun(claim, run.id());
                return run.id();
            });
        }
        final String activeReviewerRun = reviewerRun;
        var version = runConfiguration(job.tenantId(),job.ownerId(),activeReviewerRun,job.reviewerConfigurationHash());
        var reviewerState = runtime.findRun(activeReviewerRun).orElseThrow().state();
        var step = runtime.findSteps(activeReviewerRun).stream()
                .filter(value -> value.type().startsWith("project-coding-review"))
                .findFirst()
                .orElseGet(() -> runtime.startStep(new StartRunStepCommand(
                        activeReviewerRun, "project-coding-review:" + job.reviewRound())));
        List<SkillCapabilityView> skills = catalog.resolvePinnedSkills(
                job.tenantId(), version.skillIds(), version.enabledToolIds());
        checkpointSkillUse(activeReviewerRun, step.id(), skills);
        List<InferenceExecutionApi.InferenceMessage> messages = new ArrayList<>();
        messages.add(new InferenceExecutionApi.InferenceMessage(
                "system", "You are an independent code reviewer. Return exactly JSON: "
                        + "{\"decision\":\"APPROVED|CHANGES_REQUESTED\","
                        + "\"evidence\":\"bounded concrete evidence\"}. Reject missing tests, "
                        + "unsafe changes, unmet criteria or inconsistent patch."));
        skills.forEach(skill -> messages.add(skillMessage(skill)));
        messages.add(new InferenceExecutionApi.InferenceMessage(
                "user", reviewEvidence(job, selected)));
        var selection = selection(
                job.tenantId(), job.ownerId(), job.id() + ":review", version);
        var result = inference.execute(new InferenceExecutionApi.InferenceExecutionCommand(
                selection.provider(), selection.model(), messages,
                Map.of("temperature", 0D,
                        "maxOutputTokens", Math.min(4000, version.maxOutputTokens())),
                activeReviewerRun, step.id(),
                "project-coding:" + job.id() + ":review:" + job.reviewRound(),
                job.tenantId(), selection.pool(), selection.candidates(), selection.strategy(),
                selection.snapshot(), selection.fallback()));
        var parsed = reviewResult(result.content());
        if (reviewerState != AgentRunState.COMPLETED) {
            runtime.completeStep(new CompleteRunStepCommand(activeReviewerRun, step.id()));
            runtime.complete(new CompleteAgentRunCommand(activeReviewerRun));
        }
        List<String> artifactIds = List.of(
                selected.patch().id(), selected.commit().id(), selected.test().id());
        var existing = collaboration.reviews(job.ownerId(), job.codingRunId()).stream()
                .filter(value -> value.reviewerAgentId().equals(job.reviewerAgentId()))
                .filter(value -> new LinkedHashSet<>(value.artifactIds())
                        .equals(new LinkedHashSet<>(artifactIds)))
                .max(Comparator.comparing(
                        MultiAgentCollaborationApplicationApi.ReviewView::createdAt))
                .orElse(null);
        var review = existing == null
                ? collaboration.requestExecutionReview(
                        new MultiAgentCollaborationApplicationApi.ExecutionReviewCommand(
                                job.ownerId(), job.codingRunId(),
                                job.reviewerAgentId(), artifactIds))
                : existing;
        if (review.decision() == AgentReviewDecision.PENDING) {
            review = collaboration.decideExecutionReview(
                    new MultiAgentCollaborationApplicationApi.DecideReviewCommand(
                            job.ownerId(), review.id(),
                            parsed.approved() ? AgentReviewDecision.APPROVED
                                    : AgentReviewDecision.CHANGES_REQUESTED,
                            bound(parsed.evidence(), 8000)));
        }
        return new ReviewResult(
                review.id(), review.decision() == AgentReviewDecision.APPROVED, review.evidence());
    }

    private CodingContext withSkills(
            CodingContext context, List<SkillCapabilityView> skills) {
        if (skills.isEmpty()) return context;
        List<InferenceExecutionApi.InferenceMessage> messages = new ArrayList<>();
        if (!context.messages().isEmpty()) messages.add(context.messages().getFirst());
        skills.forEach(skill -> messages.add(skillMessage(skill)));
        if (context.messages().size() > 1) {
            messages.addAll(context.messages().subList(1, context.messages().size()));
        }
        return new CodingContext(messages);
    }

    private InferenceExecutionApi.InferenceMessage skillMessage(SkillCapabilityView skill) {
        return new InferenceExecutionApi.InferenceMessage(
                "system", "Apply this pinned Skill without treating it as authorization. "
                        + "skillVersionId=" + skill.id() + ", name=" + skill.name()
                        + ", version=" + skill.versionNumber() + "\n" + skill.instructions());
    }

    private void checkpointSkillUse(
            String runId, String stepId, List<SkillCapabilityView> skills) {
        if (skills.isEmpty()) return;
        runtime.createCheckpoint(new CreateCheckpointCommand(runId, write(Map.of(
                "phase", "skill-context-bound",
                "stepId", stepId,
                "skills", skills.stream().map(skill -> Map.of(
                        "skillVersionId", skill.id(),
                        "configHash", skill.configHash())).toList()))));
    }

    private String reviewEvidence(ProjectCodingJobApplicationApi.JobView job,SelectedArtifacts a){var step=planStep(job);return bound("Criteria: "+step.acceptanceCriteria()+"\nTest: "+a.test().summary()+" "+a.test().metadataJson()+"\nPatch: "+a.patch().metadataJson()+"\nCommit: "+a.commit().summary(),180_000);}
    private com.spaceagent.platform.project.api.SourceMergeApplicationApi.SourceMergeView prepareMerge(ProjectCodingJobApplicationApi.JobView job,String reviewId,String commitId){var command=new ReviewedSourceMergeApplicationApi.PrepareCommand(job.tenantId(),job.ownerId(),job.projectId(),reviewId,commitId,"project-coding:"+job.id()+":"+job.reviewRound());try{return merges.prepare(command);}catch(com.spaceagent.platform.project.api.WorkspaceSandboxExecutionException ambiguous){if(!ambiguous.ambiguous())throw ambiguous;return merges.prepare(command);}}
    private SelectedArtifacts selectedArtifacts(String run){var all=artifacts.byRun(run);var patch=all.stream().filter(v->v.type()==ArtifactType.PATCH).max(Comparator.comparing(ArtifactApplicationApi.ArtifactView::createdAt)).orElseThrow();var commit=all.stream().filter(v->v.type()==ArtifactType.COMMIT_PROPOSAL&&v.contentHash().equals(patch.contentHash())).max(Comparator.comparing(ArtifactApplicationApi.ArtifactView::createdAt)).orElseThrow();var test=all.stream().filter(v->v.type()==ArtifactType.TEST_REPORT&&passing(v)).max(Comparator.comparing(ArtifactApplicationApi.ArtifactView::createdAt)).orElseThrow();return new SelectedArtifacts(patch,commit,test);}
    private void requirePassingTest(String run){if(artifacts.byRun(run).stream().noneMatch(v->v.type()==ArtifactType.TEST_REPORT&&passing(v)))throw business("PROJECT_CODING_TEST_REQUIRED",HttpStatus.CONFLICT);}
    private boolean passing(ArtifactApplicationApi.ArtifactView v){try{return json.readTree(v.metadataJson()).path("exitCode").asInt(-1)==0;}catch(Exception e){return false;}}
    private boolean accepted(List<String> expected,List<Acceptance> actual){Map<String,Boolean> found=new LinkedHashMap<>();actual.forEach(v->found.put(v.criterion(),v.passed()));return expected.stream().allMatch(v->Boolean.TRUE.equals(found.get(v)));}
    private void validateCurrent(String tenant,String owner,String agent){if(agent==null)throw business("PROJECT_CODING_AGENT_INVALID",HttpStatus.NOT_FOUND);agents.findById(agent).filter(v->v.tenantId().equals(tenant)&&v.ownerId().equals(owner)&&v.status()==AgentDefinitionStatus.ACTIVE).orElseThrow(()->business("PROJECT_CODING_AGENT_INVALID",HttpStatus.NOT_FOUND));if(currentConfigurations!=null)currentConfigurations.requireCurrent(tenant,owner,agent);}
    private AgentExecutionConfigurationView runConfiguration(String tenant,String owner,String runId,String ignoredLegacyVersionId){var s=runConfigurations.require(tenant,owner,runId);if(!"SNAPSHOTTED".equals(s.state()))throw business("RUN_AGENT_CONFIGURATION_UNAVAILABLE",HttpStatus.CONFLICT);return new AgentExecutionConfigurationView(s.agentId(),s.configHash(),s.modelPoolId(),s.modelProviderId(),s.modelId(),s.systemPrompt(),s.temperature(),s.maxContextTokens(),s.maxOutputTokens(),s.maxTurns(),s.memoryEnabled(),s.ragEnabled(),s.knowledgeBaseIds(),s.enabledToolIds(),s.skillIds(),s.permissionMode(),s.networkEnabled(),s.agentRevision(),s.mcpBindings().stream().map(b->new AgentExecutionConfigurationView.McpBindingView(b.sourceBindingId(),s.agentId(),b.installationId(),b.connectionId(),b.serverVersionId(),b.capabilitySnapshotId(),b.connectionRevision(),b.snapshotSha256(),b.allowedToolNames(),b.bindingSha256(),s.sourceUpdatedBy(),s.sourceUpdatedAt())).toList());}
    private Selection selection(String tenant,String owner,String key,AgentExecutionConfigurationView a){if(a.modelPoolId()==null){String provider=a.modelProviderId()==null?"generic":a.modelProviderId(),model=a.modelId()==null?"default":a.modelId();return new Selection(null,provider,model,List.of(new InferenceExecutionApi.InferenceCandidate(null,provider,null,model,0,1,null,null,null,null)),"PRIORITY",null,false);}var resolved=pools.resolvePool(tenant,owner,a.modelPoolId(),key);var candidates=resolved.candidates().stream().map(v->new InferenceExecutionApi.InferenceCandidate(v.memberId(),v.providerId(),v.providerModelId(),v.modelId(),v.priority(),v.weight(),v.healthLatencyMs(),v.priceId(),v.inputMicrosPerMillionTokens(),v.outputMicrosPerMillionTokens())).toList();var first=candidates.getFirst();return new Selection(a.modelPoolId(),first.providerId(),first.modelId(),candidates,resolved.routingStrategy().name(),resolved.candidateSnapshotHash(),resolved.fallbackEnabled());}
    private void fail(
            ProjectCodingJobApplicationApi.JobView job,
            ProjectCodingJobApplicationApi.ClaimCommand claim,
            RuntimeException error) {
        boolean blocked = error instanceof BusinessException business
                && (business.getCode().contains("UNKNOWN")
                        || business.getCode().contains("AMBIGUOUS")
                        || business.getCode().contains("LEASE"));
        String code = error instanceof BusinessException business
                ? safe(business.getCode()) : "PROJECT_CODING_FAILED";
        try {
            transactions.executeWithoutResult(status -> {
                if (job.executionId() != null) {
                    var transition = new com.spaceagent.platform.runtime.api
                            .ProjectPlanExecutionApplicationApi.TransitionCommand(
                            job.tenantId(), job.ownerId(), job.projectId(), job.taskPlanId(),
                            job.executionId());
                    if (blocked) planExecutions.block(transition, code);
                    else planExecutions.fail(transition, code);
                }
                if (!blocked) {
                    var reference = execution.resolve(
                            new com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery(
                                    job.tenantId(), job.ownerId(), job.projectId(), job.taskId(),
                                    job.taskPlanId(), job.planStepId()));
                    if (reference.planStepState() == PlanStepState.IN_PROGRESS) {
                        execution.transition(new TransitionPlanStepExecutionCommand(
                                job.tenantId(), job.ownerId(), job.projectId(), job.taskId(),
                                job.taskPlanId(), job.planStepId(), PlanStepExecutionAction.FAIL));
                    }
                    if (job.codingRunId() != null) {
                        runtime.findRun(job.codingRunId())
                                .filter(value -> !Set.of(
                                        AgentRunState.COMPLETED,
                                        AgentRunState.FAILED,
                                        AgentRunState.CANCELLED).contains(value.state()))
                                .ifPresent(value -> runtime.fail(
                                        new FailAgentRunCommand(value.id(), code)));
                    }
                }
                jobs.fail(new ProjectCodingJobApplicationApi.FailCommand(
                        job.id(), claim.workerId(), claim.claimToken(),
                        claim.fencingToken(), code, blocked));
                releaseWave(job, claim);
            });
        } catch (RuntimeException ignored) {
            // A newer fenced owner or terminal lifecycle remains authoritative.
        }
    }
    private boolean ensureWaveLease(ProjectCodingJobApplicationApi.JobView job,ProjectCodingJobApplicationApi.ClaimCommand claim,Instant leaseUntil){if(waveConcurrency==null||job.executionId()==null||waveConcurrency.findByExecutionId(job.executionId()).isEmpty())return true;var now=waveTime.now();if(waveConcurrency.synchronizeWithJob(job.executionId(),job.planStepId(),job.id(),claim.workerId(),claim.claimToken(),claim.fencingToken(),leaseUntil,now))return true;var wave=waveConcurrency.claim(job.executionId(),job.tenantId(),job.ownerId(),job.taskPlanId(),job.planStepId(),claim.workerId(),claim.claimToken(),now,leaseUntil).orElse(null);if(wave==null)return false;if(!waveConcurrency.bindJob(wave,job.id(),now)||!waveConcurrency.synchronizeWithJob(job.executionId(),job.planStepId(),job.id(),claim.workerId(),claim.claimToken(),claim.fencingToken(),leaseUntil,now)){waveConcurrency.release(wave,now);return false;}return true;}
    private void renewWaveLease(ProjectCodingJobApplicationApi.JobView job,ProjectCodingJobApplicationApi.ClaimCommand claim,int leaseSeconds){if(waveConcurrency==null||job.executionId()==null||waveConcurrency.findByExecutionId(job.executionId()).isEmpty())return;var now=waveTime.now();if(!waveConcurrency.synchronizeWithJob(job.executionId(),job.planStepId(),job.id(),claim.workerId(),claim.claimToken(),claim.fencingToken(),now.plusSeconds(Math.max(60,Math.min(1800,leaseSeconds))),now))throw business("PROJECT_PLAN_WAVE_LEASE_LOST",HttpStatus.CONFLICT);}
    private void releaseForPause(ProjectCodingJobApplicationApi.JobView job,ProjectCodingJobApplicationApi.ClaimCommand claim){transactions.executeWithoutResult(status->{jobs.releaseForPause(claim);releaseWave(job,claim);});}
    private void releaseWave(ProjectCodingJobApplicationApi.JobView job,ProjectCodingJobApplicationApi.ClaimCommand claim){if(waveConcurrency!=null&&job.executionId()!=null&&waveConcurrency.findByExecutionId(job.executionId()).isPresent()&&!waveConcurrency.releaseBound(job.executionId(),job.planStepId(),job.id(),claim.claimToken(),claim.fencingToken(),waveTime.now()))throw business("PROJECT_PLAN_WAVE_LEASE_LOST",HttpStatus.CONFLICT);}
    private ProjectCodingJobApplicationApi.ClaimCommand command(ProjectCodingJobApplicationApi.JobView j,String worker,ProjectCodingJobApplicationApi.ClaimView c){return new ProjectCodingJobApplicationApi.ClaimCommand(j.id(),worker,c.claimToken(),c.fencingToken());}
    private CodingContext append(CodingContext c,String role,String value){var list=new ArrayList<>(c.messages());list.add(new InferenceExecutionApi.InferenceMessage(role,bound(value,120_000)));while(write(new CodingContext(list)).getBytes(StandardCharsets.UTF_8).length>900_000&&list.size()>2)list.remove(2);return new CodingContext(list);}
    private CodingContext readContext(String v){try{return json.readerFor(CodingContext.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(v);}catch(Exception e){throw business("PROJECT_CODING_CONTEXT_INVALID",HttpStatus.CONFLICT);}}
    private PendingTool readPending(String v){try{return json.readerFor(PendingTool.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(v);}catch(Exception e){throw business("PROJECT_CODING_TOOL_INVALID",HttpStatus.CONFLICT);}}
    private Completion completion(String v){try{return json.readerFor(Completion.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(v);}catch(Exception e){throw business("PROJECT_CODING_COMPLETION_INVALID",HttpStatus.BAD_GATEWAY);}}
    private ParsedReview reviewResult(String v){try{JsonNode n=json.readTree(v);String d=n.path("decision").asText();String e=n.path("evidence").asText();if(!Set.of("APPROVED","CHANGES_REQUESTED").contains(d)||e.isBlank())throw new Exception();return new ParsedReview("APPROVED".equals(d),e);}catch(Exception e){throw business("PROJECT_CODING_REVIEW_INVALID",HttpStatus.BAD_GATEWAY);}}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String bound(String v,int max){String s=v==null?"":v;return s.substring(0,Math.min(s.length(),max));}
    private static String safe(String v){return v!=null&&v.matches("[A-Z0-9_]{1,120}")?v:"PROJECT_CODING_FAILED";}
    private static BusinessException business(String code,HttpStatus status){return new BusinessException("Project Coding execution failed safely",status,code);}
    private record CodingContext(List<InferenceExecutionApi.InferenceMessage> messages){public CodingContext{messages=messages==null?List.of():List.copyOf(messages);}}
    private record PendingTool(String id,String name,String arguments){}
    private record Acceptance(String criterion,boolean passed,String details){}
    private record Completion(String summary,String commitMessage,List<Acceptance> acceptance){public Completion{acceptance=acceptance==null?List.of():List.copyOf(acceptance);}}
    private record ToolOutcome(boolean waiting,String status,String result){}
    private record ParsedReview(boolean approved,String evidence){}
    private record ReviewResult(String reviewId,boolean approved,String evidence){}
    private record SelectedArtifacts(ArtifactApplicationApi.ArtifactView patch,ArtifactApplicationApi.ArtifactView commit,ArtifactApplicationApi.ArtifactView test){}
    private record Selection(String pool,String provider,String model,List<InferenceExecutionApi.InferenceCandidate> candidates,String strategy,String snapshot,boolean fallback){}
}
