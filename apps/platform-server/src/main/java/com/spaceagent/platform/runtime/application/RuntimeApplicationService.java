package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.runtime.api.AcceptHandoffCommand;
import com.spaceagent.platform.runtime.api.AcceptRecoveryCommand;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.AdvanceExecutionCursorCommand;
import com.spaceagent.platform.runtime.api.CancelAgentRunCommand;
import com.spaceagent.platform.runtime.api.CancelAgentRunFencedCommand;
import com.spaceagent.platform.runtime.api.CheckpointView;
import com.spaceagent.platform.runtime.api.CompleteAgentRunCommand;
import com.spaceagent.platform.runtime.api.CompleteAgentRunFencedCommand;
import com.spaceagent.platform.runtime.api.CompleteHandoffCommand;
import com.spaceagent.platform.runtime.api.CompleteRunStepCommand;
import com.spaceagent.platform.runtime.api.CreateCheckpointCommand;
import com.spaceagent.platform.runtime.api.CreateHandoffCommand;
import com.spaceagent.platform.runtime.api.FailAgentRunCommand;
import com.spaceagent.platform.runtime.api.FailAgentRunFencedCommand;
import com.spaceagent.platform.runtime.api.FailRunStepCommand;
import com.spaceagent.platform.runtime.api.HandoffView;
import com.spaceagent.platform.runtime.api.RecoveryResumeStateView;
import com.spaceagent.platform.runtime.api.RecoveryView;
import com.spaceagent.platform.runtime.api.RequestRecoveryCommand;
import com.spaceagent.platform.runtime.api.ResumeAgentRunCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RecordRunEventCommand;
import com.spaceagent.platform.runtime.api.RunEventView;
import com.spaceagent.platform.runtime.api.RunEventPageView;
import com.spaceagent.platform.runtime.api.RuntimeOwnershipPort;
import com.spaceagent.platform.runtime.api.RunStepView;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartRunStepCommand;
import com.spaceagent.platform.runtime.api.ToolExecutionRefView;
import com.spaceagent.platform.runtime.domain.AgentRun;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshot;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshotRepository;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.Checkpoint;
import com.spaceagent.platform.runtime.domain.Handoff;
import com.spaceagent.platform.runtime.domain.HandoffState;
import com.spaceagent.platform.runtime.domain.Recovery;
import com.spaceagent.platform.runtime.domain.RecoveryResumeState;
import com.spaceagent.platform.runtime.domain.RecoveryState;
import com.spaceagent.platform.runtime.domain.RunStep;
import com.spaceagent.platform.runtime.domain.RunEvent;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.domain.RunStepState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuation;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.platform.runtime.domain.RuntimeLedgerRepository;
import com.spaceagent.platform.runtime.domain.ToolExecutionRef;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import com.spaceagent.platform.project.api.PlanStepExecutionAction;
import com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery;
import com.spaceagent.platform.project.api.ResolveChatTaskExecutionQuery;
import com.spaceagent.platform.project.api.TaskExecutionApplicationApi;
import com.spaceagent.platform.project.api.TransitionPlanStepExecutionCommand;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.memory.api.CompleteTaskMemoryConsolidationCommand;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-module runtime coordinator and recovery owner.
 *
 * <p>This class owns the durable run/step/checkpoint/recovery/handoff transitions. It
 * consumes the tooling public application API for ledger reconstruction, never another
 * module's persistence implementation.
 */
@Service
@Transactional
public class RuntimeApplicationService implements RuntimeApplicationApi, RuntimeOwnershipPort {

    private static final Logger log = LoggerFactory.getLogger(RuntimeApplicationService.class);

    private final RuntimeLedgerRepository repository;
    private final ToolExecutionLedgerApplicationApi toolLedgerApi;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final MemoryApplicationApi memoryApi;
    private final TaskExecutionApplicationApi taskExecutionApi;
    private final AgentCurrentConfigurationApplicationApi currentAgentConfigurations;
    private final AgentRunConfigurationSnapshotRepository runConfigurationSnapshots;

    public RuntimeApplicationService(
            RuntimeLedgerRepository repository,
            ToolExecutionLedgerApplicationApi toolLedgerApi,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this(repository, toolLedgerApi, objectMapper, idGenerator, timeProvider,
                null, null, null, null);
    }

    public RuntimeApplicationService(
            RuntimeLedgerRepository repository,
            ToolExecutionLedgerApplicationApi toolLedgerApi,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            MemoryApplicationApi memoryApi) {
        this(repository, toolLedgerApi, objectMapper, idGenerator, timeProvider,
                memoryApi, null, null, null);
    }

    public RuntimeApplicationService(
            RuntimeLedgerRepository repository,
            ToolExecutionLedgerApplicationApi toolLedgerApi,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            MemoryApplicationApi memoryApi,
            TaskExecutionApplicationApi taskExecutionApi) {
        this(repository, toolLedgerApi, objectMapper, idGenerator, timeProvider,
                memoryApi, taskExecutionApi, null, null);
    }

    /** Compatibility constructor for callers compiled before removal of the obsolete release dependency. */
    public RuntimeApplicationService(
            RuntimeLedgerRepository repository,
            ToolExecutionLedgerApplicationApi toolLedgerApi,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            MemoryApplicationApi memoryApi,
            Object ignoredReleaseDependency,
            TaskExecutionApplicationApi taskExecutionApi) {
        this(repository, toolLedgerApi, objectMapper, idGenerator, timeProvider,
                memoryApi, taskExecutionApi, null, null);
    }

    /** Compatibility constructor for current-config admission tests during the contract migration. */
    public RuntimeApplicationService(
            RuntimeLedgerRepository repository,
            ToolExecutionLedgerApplicationApi toolLedgerApi,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            MemoryApplicationApi memoryApi,
            Object ignoredReleaseDependency,
            TaskExecutionApplicationApi taskExecutionApi,
            AgentCurrentConfigurationApplicationApi currentAgentConfigurations,
            AgentRunConfigurationSnapshotRepository runConfigurationSnapshots) {
        this(repository, toolLedgerApi, objectMapper, idGenerator, timeProvider, memoryApi,
                taskExecutionApi, currentAgentConfigurations, runConfigurationSnapshots);
    }

    @Autowired
    public RuntimeApplicationService(
            RuntimeLedgerRepository repository,
            ToolExecutionLedgerApplicationApi toolLedgerApi,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            MemoryApplicationApi memoryApi,
            TaskExecutionApplicationApi taskExecutionApi,
            AgentCurrentConfigurationApplicationApi currentAgentConfigurations,
            AgentRunConfigurationSnapshotRepository runConfigurationSnapshots) {
        this.repository = repository;
        this.toolLedgerApi = toolLedgerApi;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.memoryApi = memoryApi;
        this.taskExecutionApi = taskExecutionApi;
        this.currentAgentConfigurations = currentAgentConfigurations;
        this.runConfigurationSnapshots = runConfigurationSnapshots;
    }

    @Override
    public AgentRunView startRun(StartAgentRunCommand command) {
        if (command.requestedRunId() != null) {
            var existing = repository.findRunById(command.requestedRunId()).orElse(null);
            if (existing != null) {
                if (!existing.ownerId().equals(command.ownerId())
                        || !existing.agentId().equals(command.agentId())
                        || !existing.conversationId().equals(command.conversationId())
                        || !java.util.Objects.equals(existing.tenantId(), command.tenantId())) {
                    throw new BusinessException("Stable Run ID is already bound",
                            HttpStatus.CONFLICT, "AUTOMATION_RUN_ID_CONFLICT");
                }
                return toView(existing);
            }
        }
        AgentCurrentConfigurationApplicationApi.ConfigurationView currentConfiguration =
                currentAgentConfigurations == null ? null : currentAgentConfigurations.requireCurrent(
                        command.tenantId(), command.ownerId(), command.agentId());
        if (command.taskScoped()) {
            if (taskExecutionApi == null) {
                throw new BusinessException(
                        "Task execution validation is unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "TASK_EXECUTION_UNAVAILABLE");
            }
            taskExecutionApi.resolve(new ResolveTaskExecutionReferenceQuery(
                    command.tenantId(), command.ownerId(), command.projectId(),
                    command.taskId(), command.taskPlanId(), command.planStepId()));
        }
        if (command.chatTaskId() != null) {
            if (taskExecutionApi == null) {
                throw new BusinessException(
                        "Chat Task execution validation is unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE, "CHAT_TASK_EXECUTION_UNAVAILABLE");
            }
            taskExecutionApi.resolveChatTask(new ResolveChatTaskExecutionQuery(
                    command.tenantId(), command.ownerId(), command.conversationId(),
                    command.chatTaskId()));
        }
        Instant now = timeProvider.now();
        String runId = command.requestedRunId() == null
                ? idGenerator.nextId() : command.requestedRunId();
        AgentRun run = new AgentRun(
                runId,
                command.agentId(),
                runId,
                command.tenantId(),
                command.ownerId(),
                command.conversationId(),
                command.chatTaskId(),
                command.projectId(),
                command.projectDirectoryId(),
                command.workspaceId(),
                command.taskId(),
                command.taskPlanId(),
                command.planStepId(),
                ExecutionCursor.initial(),
                0,
                AgentRunState.QUEUED,
                null,
                now,
                now,
                null);
        repository.createRun(run);
        if (runConfigurationSnapshots != null && currentConfiguration != null) {
            runConfigurationSnapshots.insertIfAbsent(snapshot(run, currentConfiguration, now));
        }
        appendEvent(run, RunEventType.RUN_CREATED, jsonPayload(Map.of(
                "state", run.state().name(),
                "taskScoped", run.taskScoped(),
                "workspaceScoped", run.workspaceScoped())));
        return toView(run);
    }

    private static AgentRunConfigurationSnapshot snapshot(
            AgentRun run,
            AgentCurrentConfigurationApplicationApi.ConfigurationView configuration,
            Instant capturedAt) {
        return new AgentRunConfigurationSnapshot(
                run.id(), run.id(), configuration.tenantId(), configuration.ownerUserId(),
                configuration.agentId(), AgentRunConfigurationSnapshot.State.SNAPSHOTTED,
                configuration.agentRevision(), configuration.configHash(),
                configuration.modelPoolId(), configuration.modelProviderId(),
                configuration.modelId(), configuration.systemPrompt(), configuration.temperature(),
                configuration.maxContextTokens(), configuration.maxOutputTokens(),
                configuration.maxTurns(), configuration.memoryEnabled(), configuration.ragEnabled(),
                configuration.networkEnabled(), configuration.knowledgeBaseIds(),
                configuration.enabledToolIds(), configuration.skillIds(),
                configuration.permissionMode(), configuration.mcpBindings().stream()
                        .map(binding -> new AgentRunConfigurationSnapshot.McpBinding(
                                binding.id(), binding.installationId(), binding.connectionId(),
                                binding.serverVersionId(), binding.capabilitySnapshotId(),
                                binding.connectionRevision(), binding.snapshotSha256(),
                                binding.allowedToolNames(), binding.bindingSha256()))
                        .toList(),
                configuration.updatedBy(), configuration.updatedAt(), capturedAt, configuration.knowledgeCollectionIds());
    }

    @Override
    public Optional<AgentRunView> findRun(String agentRunId) {
        return repository.findRunById(agentRunId).map(RuntimeApplicationService::toView);
    }

    @Override public Optional<AgentRunView> findLatestChatRun(String tenantId,String ownerId,String conversationId){
        return repository.findLatestChatRun(tenantId,ownerId,conversationId).map(RuntimeApplicationService::toView);
    }

    @Override
    public AgentRunView markRunInProgress(String agentRunId) {
        AgentRun current = requireNonTerminalRun(agentRunId);
        if (current.state() == AgentRunState.IN_PROGRESS) {
            return toView(current);
        }
        if (current.state() != AgentRunState.QUEUED
                && current.state() != AgentRunState.RECOVERING
                && current.state() != AgentRunState.WAITING_FOR_TOOL
                && current.state() != AgentRunState.WAITING_FOR_USER) {
            throw new BusinessException(
                    "Run cannot transition to IN_PROGRESS from " + current.state(),
                    HttpStatus.CONFLICT);
        }
        AgentRun updated = copyWithState(
                current, AgentRunState.IN_PROGRESS, null, timeProvider.now(), null);
        persistStateTransition(current, updated);
        return toView(updated);
    }

    @Override
    public AgentRunView resumeFenced(ResumeAgentRunCommand command) {
        AgentRun current = requireNonTerminalRun(command.agentRunId());
        if (!repository.workerLeaseIsActive(
                current.id(), command.leaseToken(), command.fencingToken())) {
            throw new BusinessException(
                    "Runtime worker lease is stale or expired",
                    HttpStatus.CONFLICT,
                    "RUNTIME_FENCE_REJECTED");
        }
        if (current.state() == AgentRunState.IN_PROGRESS) {
            return toView(current);
        }
        if (current.state() != AgentRunState.QUEUED
                && current.state() != AgentRunState.RECOVERING
                && current.state() != AgentRunState.WAITING_FOR_TOOL
                && current.state() != AgentRunState.WAITING_FOR_USER) {
            throw new BusinessException(
                    "Run cannot be resumed from " + current.state(),
                    HttpStatus.CONFLICT,
                    "RUNTIME_RESUME_STATE_CONFLICT");
        }
        AgentRun updated = copyWithState(
                current, AgentRunState.IN_PROGRESS, null, timeProvider.now(), null);
        synchronizeTaskExecution(updated);
        if (!repository.compareAndSetRunFenced(
                current, updated, command.leaseToken(), command.fencingToken())) {
            throw new BusinessException(
                    "Runtime fenced mutation lost the active lease or revision",
                    HttpStatus.CONFLICT,
                    "RUNTIME_FENCE_REJECTED");
        }
        appendEvent(updated, RunEventType.RUN_STATE_CHANGED, jsonPayload(Map.of(
                "previousState", current.state().name(),
                "state", updated.state().name(),
                "revision", updated.revision(),
                "fencingToken", command.fencingToken())));
        return toView(updated);
    }

    @Override
    public AgentRunView completeFenced(CompleteAgentRunFencedCommand command) {
        AgentRun current = requireNonTerminalRun(command.agentRunId());
        AgentRun updated = copyWithState(
                current, AgentRunState.COMPLETED, null, timeProvider.now(), timeProvider.now());
        persistFencedTerminal(current, updated, command.leaseToken(), command.fencingToken());
        consolidateTaskMemoryIfPresent(updated);
        return toView(updated);
    }

    @Override
    public AgentRunView failFenced(FailAgentRunFencedCommand command) {
        if (command.reason() == null || command.reason().isBlank()) {
            throw new IllegalArgumentException("Fenced failure reason is required");
        }
        AgentRun current = requireNonTerminalRun(command.agentRunId());
        AgentRun updated = copyWithState(
                current, AgentRunState.FAILED, command.reason(),
                timeProvider.now(), timeProvider.now());
        persistFencedTerminal(current, updated, command.leaseToken(), command.fencingToken());
        return toView(updated);
    }

    @Override
    public AgentRunView cancelFenced(CancelAgentRunFencedCommand command) {
        AgentRun current = requireNonTerminalRun(command.agentRunId());
        AgentRun updated = copyWithState(
                current, AgentRunState.CANCELLED, command.reason(),
                timeProvider.now(), timeProvider.now());
        persistFencedTerminal(current, updated, command.leaseToken(), command.fencingToken());
        return toView(updated);
    }

    @Override
    public AgentRunView markRunWaitingForTool(String agentRunId) {
        AgentRun current = requireNonTerminalRun(agentRunId);
        if (current.state() == AgentRunState.WAITING_FOR_TOOL) {
            return toView(current);
        }
        if (current.state() != AgentRunState.IN_PROGRESS && current.state() != AgentRunState.QUEUED) {
            throw new BusinessException(
                    "Run cannot transition to WAITING_FOR_TOOL from " + current.state(),
                    HttpStatus.CONFLICT);
        }
        AgentRun updated = copyWithState(
                current, AgentRunState.WAITING_FOR_TOOL, null, timeProvider.now(), null);
        persistStateTransition(current, updated);
        return toView(updated);
    }

    @Override
    public AgentRunView markRunWaitingForUser(String agentRunId) {
        AgentRun current = requireNonTerminalRun(agentRunId);
        if (current.state() == AgentRunState.WAITING_FOR_USER) {
            return toView(current);
        }
        if (current.state() != AgentRunState.IN_PROGRESS
                && current.state() != AgentRunState.QUEUED
                && current.state() != AgentRunState.WAITING_FOR_TOOL) {
            throw new BusinessException(
                    "Run cannot transition to WAITING_FOR_USER from " + current.state(),
                    HttpStatus.CONFLICT);
        }
        AgentRun updated = copyWithState(
                current, AgentRunState.WAITING_FOR_USER, null, timeProvider.now(), null);
        persistStateTransition(current, updated);
        return toView(updated);
    }

    @Override
    public AgentRunView markRunHandedOff(String agentRunId, String handoffId) {
        if (handoffId == null || handoffId.isBlank()) {
            throw new IllegalArgumentException("handoffId must not be blank");
        }
        AgentRun current = requireRun(agentRunId);
        if (current.state() == AgentRunState.CANCELLED
                && current.failureReason() != null && current.failureReason().startsWith("handoff:")) {
            if (!Objects.equals(current.failureReason(), "handoff:" + handoffId)) {
                throw new BusinessException(
                        "Agent run was handed off by another request",
                        HttpStatus.CONFLICT,
                        "PROJECT_HANDOFF_SOURCE_CONFLICT");
            }
            return toView(current);
        }
        if (isTerminal(current.state())) {
            throw new BusinessException(
                    "Terminal Agent run cannot be handed off",
                    HttpStatus.CONFLICT,
                    "PROJECT_HANDOFF_SOURCE_TERMINAL");
        }
        Instant now = timeProvider.now();
        AgentRun updated = copyWithState(
                current, AgentRunState.CANCELLED, "handoff:" + handoffId, now, now);
        persistRunCas(current, updated);
        repository.cancelPendingContinuations(current.id(), "AgentRun handed off");
        appendEvent(updated, RunEventType.RUN_STATE_CHANGED, jsonPayload(Map.of(
                "previousState", current.state().name(),
                "state", updated.state().name(),
                "revision", updated.revision(),
                "handoffId", handoffId)));
        return toView(updated);
    }

    @Override
    public RunStepView startStep(StartRunStepCommand command) {
        AgentRun run = requireNonTerminalRun(command.agentRunId());
        int nextSequence = repository.findStepsByRunId(run.id()).stream()
                .mapToInt(RunStep::sequence)
                .max()
                .orElse(-1) + 1;
        Instant now = timeProvider.now();
        RunStep step = new RunStep(
                idGenerator.nextId(),
                run.id(),
                nextSequence,
                command.type(),
                RunStepState.PENDING,
                now,
                null);
        AgentRun eventRun = run;
        if (run.state() != AgentRunState.IN_PROGRESS) {
            eventRun = copyWithState(run, AgentRunState.IN_PROGRESS, null, now, null);
            persistStateTransition(run, eventRun);
        }
        repository.saveStep(step);
        appendEvent(eventRun, RunEventType.STEP_STARTED, jsonPayload(Map.of(
                "runStepId", step.id(), "type", step.type(), "sequence", step.sequence())));
        return toView(step);
    }

    @Override
    public RunStepView completeStep(CompleteRunStepCommand command) {
        requireNonTerminalRun(command.agentRunId());
        RunStep current = requireStep(command.agentRunId(), command.runStepId());
        if (current.state() == RunStepState.COMPLETED) {
            return toView(current);
        }
        if (current.state() == RunStepState.FAILED || current.state() == RunStepState.SKIPPED) {
            throw new BusinessException(
                    "Run step cannot be completed from " + current.state(),
                    HttpStatus.CONFLICT);
        }
        Instant now = timeProvider.now();
        RunStep updated = new RunStep(
                current.id(),
                current.agentRunId(),
                current.sequence(),
                current.type(),
                RunStepState.COMPLETED,
                current.createdAt(),
                now);
        repository.saveStep(updated);
        appendEvent(requireRun(command.agentRunId()), RunEventType.STEP_COMPLETED,
                jsonPayload(Map.of("runStepId", updated.id(), "sequence", updated.sequence())));
        return toView(updated);
    }

    @Override
    public RunStepView failStep(FailRunStepCommand command) {
        requireNonTerminalRun(command.agentRunId());
        RunStep current = requireStep(command.agentRunId(), command.runStepId());
        if (current.state() == RunStepState.FAILED) {
            return toView(current);
        }
        Instant now = timeProvider.now();
        RunStep updated = new RunStep(
                current.id(),
                current.agentRunId(),
                current.sequence(),
                current.type(),
                RunStepState.FAILED,
                current.createdAt(),
                now);
        repository.saveStep(updated);
        appendEvent(requireRun(command.agentRunId()), RunEventType.STEP_FAILED,
                jsonPayload(Map.of("runStepId", updated.id(), "sequence", updated.sequence())));
        return toView(updated);
    }

    @Override
    public List<RunStepView> findSteps(String agentRunId) {
        return repository.findStepsByRunId(agentRunId).stream()
                .sorted(Comparator.comparingInt(RunStep::sequence))
                .map(RuntimeApplicationService::toView)
                .toList();
    }

    @Override
    public CheckpointView createCheckpoint(CreateCheckpointCommand command) {
        AgentRun run = requireNonTerminalRun(command.agentRunId());
        int nextSequence = repository.findLatestCheckpoint(run.id())
                .map(checkpoint -> checkpoint.sequence() + 1)
                .orElse(0);
        Checkpoint checkpoint = new Checkpoint(
                idGenerator.nextId(),
                run.id(),
                nextSequence,
                command.stateSnapshot(),
                timeProvider.now());
        repository.saveCheckpoint(checkpoint);
        CheckpointPayload checkpointPayload = parseCheckpoint(checkpoint.stateSnapshot());
        ExecutionCursor previous = run.executionCursor();
        ExecutionCursor cursor = new ExecutionCursor(
                checkpointPayload.phase() == null ? previous.phase() : checkpointPayload.phase(),
                checkpointPayload.cursor() == null ? previous.stepId() : checkpointPayload.cursor(),
                checkpoint.id(), checkpoint.sequence());
        AgentRun cursorUpdated = copyWithCursor(run, cursor, timeProvider.now());
        persistRunCas(run, cursorUpdated);
        appendEvent(cursorUpdated, RunEventType.CHECKPOINT_CREATED, jsonPayload(Map.of(
                "checkpointId", checkpoint.id(), "sequence", checkpoint.sequence())));
        appendEvent(cursorUpdated, RunEventType.CURSOR_ADVANCED, jsonPayload(Map.of(
                "phase", cursor.phase(), "checkpointId", checkpoint.id())));
        return toView(checkpoint);
    }

    @Override
    public Optional<CheckpointView> findLatestCheckpoint(String agentRunId) {
        return repository.findLatestCheckpoint(agentRunId)
                .map(RuntimeApplicationService::toView);
    }

    @Override
    public Optional<CheckpointView> findLatestCheckpointByPhase(
            String agentRunId, String phase) {
        if (phase == null || !phase.matches("[a-z0-9-]{1,80}")) {
            throw new IllegalArgumentException("Checkpoint phase is invalid");
        }
        return repository.findLatestCheckpointByPhase(agentRunId, phase)
                .map(RuntimeApplicationService::toView);
    }

    @Override
    public RecoveryView requestRecovery(RequestRecoveryCommand command) {
        AgentRun current = requireNonTerminalRun(command.agentRunId());
        int nextAttempt = repository.findRecoveriesByRunId(current.id()).stream()
                .mapToInt(Recovery::attempt)
                .max()
                .orElse(-1) + 1;
        Instant now = timeProvider.now();

        if (repository.findLatestCheckpoint(current.id()).isEmpty()) {
            Recovery recovery = new Recovery(
                    idGenerator.nextId(),
                    current.id(),
                    nextAttempt,
                    RecoveryState.FAILED,
                    "No checkpoint available for recovery: " + command.reason(),
                    now,
                    now);
            AgentRun failed = copyWithState(
                    current,
                    AgentRunState.FAILED,
                    recovery.reason(),
                    now,
                    now);
            repository.saveRecovery(recovery);
            persistStateTransition(current, failed);
            return toView(recovery);
        }

        Recovery recovery = new Recovery(
                idGenerator.nextId(),
                current.id(),
                nextAttempt,
                RecoveryState.IN_PROGRESS,
                command.reason(),
                now,
                null);
        AgentRun recovering = copyWithState(
                current,
                AgentRunState.RECOVERING,
                null,
                now,
                null);
        repository.saveRecovery(recovery);
        persistStateTransition(current, recovering);
        return toView(recovery);
    }

    @Override
    public Optional<RecoveryResumeStateView> reconstructResumeState(String agentRunId) {
        AgentRun run = repository.findRunById(agentRunId)
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found: " + agentRunId,
                        HttpStatus.NOT_FOUND));
        Checkpoint checkpoint = repository.findLatestCheckpoint(agentRunId)
                .orElseThrow(() -> new BusinessException(
                        "No checkpoint available for run " + agentRunId,
                        HttpStatus.CONFLICT));
        RunStep unfinishedStep = findUnfinishedStep(agentRunId).orElse(null);
        CheckpointPayload checkpointPayload = parseCheckpoint(checkpoint.stateSnapshot());
        List<ToolExecutionRefView> toolExecutions = toolLedgerApi.findByRunId(agentRunId).stream()
                .map(RuntimeApplicationService::toToolRefView)
                .toList();

        RecoveryResumeState state = new RecoveryResumeState(
                run.id(),
                run.agentId(),
                run.configurationSnapshotId(),
                run.ownerId(),
                run.conversationId(),
                run.chatTaskId(),
                run.projectId(),
                run.projectDirectoryId(),
                run.workspaceId(),
                run.taskId(),
                run.state(),
                checkpoint.id(),
                checkpoint.sequence(),
                checkpoint.stateSnapshot(),
                unfinishedStep == null ? null : unfinishedStep.id(),
                unfinishedStep == null ? null : unfinishedStep.type(),
                unfinishedStep == null ? null : unfinishedStep.state(),
                checkpointPayload.conversationSnapshotId(),
                checkpointPayload.conversationSnapshotVersion(),
                checkpointPayload.phase(),
                checkpointPayload.cursor(),
                toolExecutions.stream()
                        .map(ref -> new ToolExecutionRef(
                                ref.toolCallId(), ref.status(), ref.resultRef()))
                        .toList(),
                timeProvider.now());
        return Optional.of(toView(state));
    }

    @Override
    public RecoveryView acceptRecovery(AcceptRecoveryCommand command) {
        AgentRun run = repository.findRunById(command.agentRunId())
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found: " + command.agentRunId(),
                        HttpStatus.NOT_FOUND));
        Recovery current = repository.findRecoveriesByRunId(run.id()).stream()
                .filter(recovery -> command.recoveryId().equals(recovery.id()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "Recovery not found: " + command.recoveryId(),
                        HttpStatus.NOT_FOUND));
        if (current.state() != RecoveryState.IN_PROGRESS) {
            throw new BusinessException(
                    "Recovery cannot be accepted from " + current.state(),
                    HttpStatus.CONFLICT);
        }

        RecoveryResumeStateView reconstructed = reconstructResumeState(run.id())
                .orElseThrow(() -> new BusinessException(
                        "Unable to reconstruct a resumable state for run " + run.id(),
                        HttpStatus.CONFLICT));
        if (!sameDurableResumeState(reconstructed, command.resumeState())) {
            throw new BusinessException(
                    "Recovery resume state does not match the reconstructed state",
                    HttpStatus.CONFLICT,
                    "RECOVERY_RESUME_STATE_MISMATCH");
        }

        Instant now = timeProvider.now();
        Recovery updated = new Recovery(
                current.id(),
                current.agentRunId(),
                current.attempt(),
                RecoveryState.COMPLETED,
                current.reason(),
                current.createdAt(),
                now);
        repository.saveRecovery(updated);
        RuntimeContinuation proposed = new RuntimeContinuation(
                idGenerator.nextId(),
                run.id(),
                RuntimeContinuationType.RESUME_RUN,
                "recovery:" + current.id(),
                jsonPayload(Map.of(
                        "recoveryId", current.id(),
                        "checkpointId", reconstructed.latestCheckpointId())),
                RuntimeContinuationState.PENDING,
                now,
                0,
                5,
                null,
                null,
                null,
                null,
                0,
                null,
                now,
                now,
                null);
        RuntimeContinuation saved = repository.saveContinuationIfAbsent(proposed);
        if (saved.id().equals(proposed.id())) {
            appendEvent(run, RunEventType.CONTINUATION_ENQUEUED, jsonPayload(Map.of(
                    "continuationId", saved.id(),
                    "type", saved.type().name(),
                    "deduplicationKey", saved.deduplicationKey())));
        }
        return toView(updated);
    }

    private boolean sameDurableResumeState(
            RecoveryResumeStateView left,
            RecoveryResumeStateView right) {
        return Objects.equals(left.agentRunId(), right.agentRunId())
                && Objects.equals(left.agentId(), right.agentId())
                && Objects.equals(left.configurationSnapshotId(), right.configurationSnapshotId())
                && Objects.equals(left.ownerId(), right.ownerId())
                && Objects.equals(left.conversationId(), right.conversationId())
                && Objects.equals(left.projectId(), right.projectId())
                && Objects.equals(left.taskId(), right.taskId())
                && left.runState() == right.runState()
                && Objects.equals(left.latestCheckpointId(), right.latestCheckpointId())
                && left.latestCheckpointSequence() == right.latestCheckpointSequence()
                && Objects.equals(left.checkpointSnapshot(), right.checkpointSnapshot())
                && Objects.equals(left.currentRunStepId(), right.currentRunStepId())
                && Objects.equals(left.currentRunStepType(), right.currentRunStepType())
                && left.currentRunStepState() == right.currentRunStepState()
                && Objects.equals(
                        left.conversationContextSnapshotId(), right.conversationContextSnapshotId())
                && Objects.equals(
                        left.conversationContextSnapshotVersion(), right.conversationContextSnapshotVersion())
                && Objects.equals(left.resumePhase(), right.resumePhase())
                && Objects.equals(left.resumeCursor(), right.resumeCursor())
                && Objects.equals(left.toolExecutions(), right.toolExecutions());
    }

    @Override
    public AgentRunView cancel(CancelAgentRunCommand command) {
        AgentRun current = requireNonTerminalRun(command.agentRunId());
        AgentRun updated = copyWithState(
                current,
                AgentRunState.CANCELLED,
                command.reason(),
                timeProvider.now(),
                timeProvider.now());
        persistStateTransition(current, updated);
        return toView(updated);
    }

    @Override
    public AgentRunView complete(CompleteAgentRunCommand command) {
        AgentRun current = requireNonTerminalRun(command.agentRunId());
        AgentRun updated = copyWithState(
                current,
                AgentRunState.COMPLETED,
                null,
                timeProvider.now(),
                timeProvider.now());
        persistStateTransition(current, updated);
        consolidateTaskMemoryIfPresent(current);
        return toView(updated);
    }

    private void consolidateTaskMemoryIfPresent(AgentRun run) {
        if (memoryApi == null || run.taskId() == null || run.taskId().isBlank()) {
            return;
        }
        try {
            memoryApi.consolidateTask(CompleteTaskMemoryConsolidationCommand.defaults(
                    run.taskId(),
                    run.projectId(),
                    run.ownerId()));
        } catch (RuntimeException error) {
            log.warn(
                    "Task memory consolidation failed after run completion: runId={}, taskId={}, reason={}",
                    run.id(),
                    run.taskId(),
                    error.getMessage(),
                    error);
        }
    }

    @Override
    public AgentRunView fail(FailAgentRunCommand command) {
        AgentRun current = requireNonTerminalRun(command.agentRunId());
        AgentRun updated = copyWithState(
                current,
                AgentRunState.FAILED,
                command.reason(),
                timeProvider.now(),
                timeProvider.now());
        persistStateTransition(current, updated);
        return toView(updated);
    }

    @Override
    public HandoffView createHandoff(CreateHandoffCommand command) {
        repository.findRunById(command.sourceAgentRunId())
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found: " + command.sourceAgentRunId(),
                        HttpStatus.NOT_FOUND));
        Instant now = timeProvider.now();
        Handoff handoff = new Handoff(
                idGenerator.nextId(),
                command.sourceAgentRunId(),
                null,
                HandoffState.PENDING,
                command.snapshot(),
                now,
                null);
        repository.saveHandoff(handoff);
        return toView(handoff);
    }

    @Override
    public Optional<HandoffView> findHandoff(String handoffId) {
        return repository.findHandoffById(handoffId).map(RuntimeApplicationService::toView);
    }

    @Override
    public HandoffView acceptHandoff(AcceptHandoffCommand command) {
        Handoff current = requireHandoff(command.handoffId());
        if (current.state() == HandoffState.ACCEPTED) {
            return toView(current);
        }
        if (current.state() != HandoffState.PENDING) {
            throw new BusinessException(
                    "Handoff cannot be accepted from " + current.state(),
                    HttpStatus.CONFLICT);
        }
        Handoff updated = new Handoff(
                current.id(),
                current.sourceAgentRunId(),
                command.targetAgentRunId(),
                HandoffState.ACCEPTED,
                current.snapshot(),
                current.createdAt(),
                null);
        repository.saveHandoff(updated);
        return toView(updated);
    }

    @Override
    public HandoffView completeHandoff(CompleteHandoffCommand command) {
        Handoff current = requireHandoff(command.handoffId());
        if (current.state() == HandoffState.COMPLETED) {
            return toView(current);
        }
        if (current.state() != HandoffState.ACCEPTED) {
            throw new BusinessException(
                    "Handoff cannot be completed from " + current.state(),
                    HttpStatus.CONFLICT);
        }
        Handoff updated = new Handoff(
                current.id(),
                current.sourceAgentRunId(),
                current.targetAgentRunId(),
                HandoffState.COMPLETED,
                current.snapshot(),
                current.createdAt(),
                timeProvider.now());
        repository.saveHandoff(updated);
        return toView(updated);
    }

    @Override
    public List<HandoffView> findHandoffsBySourceRun(String sourceAgentRunId) {
        return repository.findHandoffsBySourceRunId(sourceAgentRunId).stream()
                .sorted(Comparator.comparing(Handoff::createdAt))
                .map(RuntimeApplicationService::toView)
                .toList();
    }

    @Override
    public AgentRunView advanceCursor(AdvanceExecutionCursorCommand command) {
        AgentRun current = requireNonTerminalRun(command.agentRunId());
        ExecutionCursor previous = current.executionCursor();
        ExecutionCursor cursor = new ExecutionCursor(
                command.phase(), command.stepId(), previous.checkpointId(),
                previous.checkpointSequence());
        AgentRun updated = copyWithCursor(current, cursor, timeProvider.now());
        persistRunCas(current, updated);
        appendEvent(updated, RunEventType.CURSOR_ADVANCED, jsonPayload(Map.of(
                "phase", cursor.phase(),
                "stepId", cursor.stepId() == null ? "" : cursor.stepId())));
        return toView(updated);
    }

    @Override
    public RunEventView recordEvent(RecordRunEventCommand command) {
        if (command.type() != RunEventType.ORCHESTRATION_COMMAND_ACCEPTED && command.type() != RunEventType.RESOURCE_OBSERVED) {
            throw new IllegalArgumentException(
                    "Only external orchestration command events may be recorded explicitly");
        }
        AgentRun run = command.type() == RunEventType.RESOURCE_OBSERVED ? requireRun(command.agentRunId()) : requireNonTerminalRun(command.agentRunId());
        validateJsonObject(command.payload());
        if (command.type() == RunEventType.RESOURCE_OBSERVED) {
            try { ResourceObservationPayloadPolicy.validate(objectMapper.readTree(command.payload())); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Invalid resource observation"); }
        }
        return toView(appendEvent(run, command.type(), command.payload()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunEventView> findEvents(String agentRunId) {
        requireRun(agentRunId);
        return repository.findEventsByRunId(agentRunId).stream()
                .sorted(Comparator.comparingLong(RunEvent::sequence))
                .map(RuntimeApplicationService::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RunEventPageView findEventsAfter(
            String agentRunId,
            long afterSequence,
            int limit) {
        if (afterSequence < -1 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException(
                    "afterSequence must be >= -1 and limit must be between 1 and 500");
        }
        AgentRun run = requireRun(agentRunId);
        List<RunEventView> events = repository
                .findEventsByRunIdAfter(agentRunId, afterSequence, limit).stream()
                .map(RuntimeApplicationService::toView)
                .toList();
        long nextSequence = events.isEmpty()
                ? afterSequence
                : events.getLast().sequence();
        return new RunEventPageView(
                agentRunId,
                afterSequence,
                nextSequence,
                isTerminal(run.state()),
                events);
    }

    @Override
    @Transactional(readOnly = true)
    public long latestEventSequence(String agentRunId) {
        requireRun(agentRunId);
        return repository.findLatestEventSequence(agentRunId);
    }

    @Override
    public boolean canObserve(String agentRunId, String tenantId, String principalId) {
        return repository.findRunById(agentRunId)
                .filter(run -> tenantId.equals(run.tenantId()) && principalId.equals(run.ownerId()))
                .isPresent();
    }

    private AgentRun requireNonTerminalRun(String agentRunId) {
        AgentRun run = requireRun(agentRunId);
        if (isTerminal(run.state())) {
            throw new BusinessException(
                    "Agent run is already terminal: " + run.state(),
                    HttpStatus.CONFLICT);
        }
        return run;
    }

    private AgentRun requireRun(String agentRunId) {
        return repository.findRunById(agentRunId)
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found: " + agentRunId,
                        HttpStatus.NOT_FOUND));
    }

    private RunStep requireStep(String agentRunId, String runStepId) {
        RunStep step = repository.findStepById(runStepId)
                .orElseThrow(() -> new BusinessException(
                        "Run step not found: " + runStepId,
                        HttpStatus.NOT_FOUND));
        if (!agentRunId.equals(step.agentRunId())) {
            throw new BusinessException(
                    "Run step " + runStepId + " does not belong to run " + agentRunId,
                    HttpStatus.CONFLICT);
        }
        return step;
    }

    private Handoff requireHandoff(String handoffId) {
        return repository.findHandoffById(handoffId)
                .orElseThrow(() -> new BusinessException(
                        "Handoff not found: " + handoffId,
                        HttpStatus.NOT_FOUND));
    }

    private Optional<RunStep> findUnfinishedStep(String agentRunId) {
        return repository.findStepsByRunId(agentRunId).stream()
                .filter(step -> step.state() == RunStepState.PENDING
                        || step.state() == RunStepState.IN_PROGRESS)
                .max(Comparator.comparingInt(RunStep::sequence));
    }

    private CheckpointPayload parseCheckpoint(String stateSnapshot) {
        try {
            JsonNode root = objectMapper.readTree(stateSnapshot);
            String phase = textOrNull(root, "phase");
            String cursor = textOrNull(root, "stepId");
            String snapshotId = textOrNull(root, "conversationSnapshotId");
            Integer snapshotVersion = root.hasNonNull("conversationSnapshotVersion")
                    ? root.get("conversationSnapshotVersion").asInt()
                    : null;
            return new CheckpointPayload(phase, cursor, snapshotId, snapshotVersion);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to parse runtime checkpoint", error);
        }
    }

    private static String textOrNull(JsonNode root, String field) {
        return root.hasNonNull(field) ? root.get(field).asText() : null;
    }

    private static AgentRun copyWithState(
            AgentRun current,
            AgentRunState state,
            String failureReason,
            Instant updatedAt,
            Instant completedAt) {
        return new AgentRun(
                current.id(),
                current.agentId(),
                current.configurationSnapshotId(),
                current.tenantId(),
                current.ownerId(),
                current.conversationId(),
                current.chatTaskId(),
                current.projectId(),
                current.projectDirectoryId(),
                current.workspaceId(),
                current.taskId(),
                current.taskPlanId(),
                current.planStepId(),
                current.executionCursor(),
                current.revision() + 1,
                state,
                failureReason,
                current.createdAt(),
                updatedAt,
                completedAt);
    }

    private static AgentRun copyWithCursor(
            AgentRun current, ExecutionCursor cursor, Instant updatedAt) {
        return new AgentRun(
                current.id(), current.agentId(), current.configurationSnapshotId(),
                current.tenantId(), current.ownerId(), current.conversationId(),
                current.chatTaskId(), current.projectId(),
                current.projectDirectoryId(), current.workspaceId(),
                current.taskId(), current.taskPlanId(),
                current.planStepId(), cursor, current.revision() + 1,
                current.state(), current.failureReason(), current.createdAt(),
                updatedAt, current.completedAt());
    }

    private void persistStateTransition(AgentRun current, AgentRun updated) {
        synchronizeTaskExecution(updated);
        persistRunCas(current, updated);
        if (isTerminal(updated.state())) {
            repository.cancelPendingContinuations(
                    updated.id(), "AgentRun became " + updated.state().name());
        }
        appendEvent(updated, RunEventType.RUN_STATE_CHANGED, jsonPayload(Map.of(
                "previousState", current.state().name(),
                "state", updated.state().name(),
                "revision", updated.revision())));
    }

    private void persistFencedTerminal(
            AgentRun current, AgentRun updated, String leaseToken, long fencingToken) {
        synchronizeTaskExecution(updated);
        if (!repository.compareAndSetRunFenced(current, updated, leaseToken, fencingToken)) {
            throw new BusinessException(
                    "Runtime fenced terminal mutation lost the active lease or revision",
                    HttpStatus.CONFLICT,
                    "RUNTIME_FENCE_REJECTED");
        }
        appendEvent(updated, RunEventType.RUN_STATE_CHANGED, jsonPayload(Map.of(
                "previousState", current.state().name(),
                "state", updated.state().name(),
                "revision", updated.revision(),
                "fencingToken", fencingToken)));
    }

    private void synchronizeTaskExecution(AgentRun run) {
        if (!run.taskScoped() || taskExecutionApi == null) {
            return;
        }
        PlanStepExecutionAction action = switch (run.state()) {
            case IN_PROGRESS -> PlanStepExecutionAction.START;
            case COMPLETED -> PlanStepExecutionAction.COMPLETE;
            case FAILED -> PlanStepExecutionAction.FAIL;
            case CANCELLED -> PlanStepExecutionAction.CANCEL;
            default -> null;
        };
        if (action == null) {
            return;
        }
        var reference = taskExecutionApi.resolve(new ResolveTaskExecutionReferenceQuery(
                run.tenantId(), run.ownerId(), run.projectId(), run.taskId(),
                run.taskPlanId(), run.planStepId()));
        if ((action == PlanStepExecutionAction.START
                && reference.planStepState() == PlanStepState.IN_PROGRESS)
                || (action == PlanStepExecutionAction.COMPLETE
                && reference.planStepState() == PlanStepState.COMPLETED)
                || (action == PlanStepExecutionAction.FAIL
                && reference.planStepState() == PlanStepState.FAILED)
                || (action == PlanStepExecutionAction.CANCEL
                && reference.planStepState() == PlanStepState.CANCELLED)) {
            return;
        }
        taskExecutionApi.transition(new TransitionPlanStepExecutionCommand(
                run.tenantId(), run.ownerId(), run.projectId(), run.taskId(),
                run.taskPlanId(), run.planStepId(), action));
    }

    private RunEvent appendEvent(AgentRun run, RunEventType type, String payload) {
        return repository.appendEvent(
                run.id(), type, payload, run.executionCursor(), timeProvider.now());
    }

    private void persistRunCas(AgentRun expected, AgentRun updated) {
        if (!repository.compareAndSetRun(expected, updated)) {
            throw new BusinessException(
                    "AgentRun revision changed concurrently",
                    HttpStatus.CONFLICT,
                    "RUNTIME_REVISION_CONFLICT");
        }
    }

    private String jsonPayload(Map<String, ?> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize RunEvent payload", error);
        }
    }

    private void validateJsonObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("RunEvent payload must be a JSON object");
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("RunEvent payload must be valid JSON", error);
        }
    }

    private static boolean isTerminal(AgentRunState state) {
        return state == AgentRunState.COMPLETED
                || state == AgentRunState.FAILED
                || state == AgentRunState.CANCELLED;
    }

    private static AgentRunView toView(AgentRun run) {
        return new AgentRunView(
                run.id(),
                run.agentId(),
                run.configurationSnapshotId(),
                run.tenantId(),
                run.ownerId(),
                run.conversationId(),
                run.chatTaskId(),
                run.projectId(),
                run.projectDirectoryId(),
                run.workspaceId(),
                run.taskId(),
                run.taskPlanId(),
                run.planStepId(),
                run.executionCursor(),
                run.revision(),
                run.state(),
                run.failureReason(),
                run.createdAt(),
                run.updatedAt(),
                run.completedAt());
    }

    private static RunEventView toView(RunEvent event) {
        return new RunEventView(
                event.id(), event.agentRunId(), event.sequence(), event.type(),
                event.payload(), event.cursor(), event.createdAt());
    }

    private static RunStepView toView(RunStep step) {
        return new RunStepView(
                step.id(),
                step.agentRunId(),
                step.sequence(),
                step.type(),
                step.state(),
                step.createdAt(),
                step.completedAt());
    }

    private static CheckpointView toView(Checkpoint checkpoint) {
        return new CheckpointView(
                checkpoint.id(),
                checkpoint.agentRunId(),
                checkpoint.sequence(),
                checkpoint.stateSnapshot(),
                checkpoint.createdAt());
    }

    private static RecoveryView toView(Recovery recovery) {
        return new RecoveryView(
                recovery.id(),
                recovery.agentRunId(),
                recovery.attempt(),
                recovery.state(),
                recovery.reason(),
                recovery.createdAt(),
                recovery.completedAt());
    }

    private static HandoffView toView(Handoff handoff) {
        return new HandoffView(
                handoff.id(),
                handoff.sourceAgentRunId(),
                handoff.targetAgentRunId(),
                handoff.state(),
                handoff.snapshot(),
                handoff.createdAt(),
                handoff.completedAt());
    }

    private static RecoveryResumeStateView toView(RecoveryResumeState state) {
        return new RecoveryResumeStateView(
                state.agentRunId(),
                state.agentId(),
                state.configurationSnapshotId(),
                state.ownerId(),
                state.conversationId(),
                state.chatTaskId(),
                state.projectId(),
                state.projectDirectoryId(),
                state.workspaceId(),
                state.taskId(),
                state.runState(),
                state.latestCheckpointId(),
                state.latestCheckpointSequence(),
                state.checkpointSnapshot(),
                state.currentRunStepId(),
                state.currentRunStepType(),
                state.currentRunStepState(),
                state.conversationContextSnapshotId(),
                state.conversationContextSnapshotVersion(),
                state.resumePhase(),
                state.resumeCursor(),
                state.toolExecutions().stream()
                        .map(ref -> new ToolExecutionRefView(
                                ref.toolCallId(), ref.status(), ref.resultRef()))
                        .toList(),
                state.reconstructedAt());
    }

    private static ToolExecutionRefView toToolRefView(ToolExecutionLedgerView entry) {
        return new ToolExecutionRefView(
                entry.toolCallId(),
                entry.status().name(),
                entry.resultRef());
    }

    private record CheckpointPayload(
            String phase,
            String cursor,
            String conversationSnapshotId,
            Integer conversationSnapshotVersion) {
    }
}
