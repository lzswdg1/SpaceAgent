package com.spaceagent.platform.runtime.application;

import static com.spaceagent.platform.runtime.application.ChatResponseFormatting.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.runtime.api.AgentExecutionConfigurationView;
import com.spaceagent.platform.context.api.CompileContextCommand;
import com.spaceagent.platform.context.api.ContextCompilerApplicationApi;
import com.spaceagent.platform.context.domain.ContextPackage;
import com.spaceagent.platform.context.domain.ContextSource;
import com.spaceagent.platform.context.domain.ContextSourceType;
import com.spaceagent.platform.conversation.api.AppendMessageCommand;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationContextSnapshotApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationContextSnapshotView;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.api.ReservedReplyView;
import com.spaceagent.platform.conversation.api.SaveConversationContextSnapshotCommand;
import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.inference.api.ModelPoolResolutionView;
import com.spaceagent.platform.inference.api.ResolvedModelCandidateView;
import com.spaceagent.platform.knowledge.api.KnowledgeApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalCommand;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalMatchView;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalView;
import com.spaceagent.platform.project.api.CreateChatRootTaskCommand;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskTransition;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanView;
import com.spaceagent.platform.project.api.GetChatTaskPlanBySourceRunQuery;
import com.spaceagent.platform.project.api.ChatTaskPlanActionCommand;
import com.spaceagent.platform.project.api.TaskPlanAction;
import com.spaceagent.platform.project.api.PlanStepExecutionAction;
import com.spaceagent.platform.project.api.TransitionChatPlanStepExecutionCommand;
import com.spaceagent.platform.project.api.TransitionChatTaskCommand;
import com.spaceagent.platform.memory.api.EvaluateMessageMemoryCommand;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.memory.api.MemoryEvaluationView;
import com.spaceagent.platform.memory.api.MemoryRecallCommand;
import com.spaceagent.platform.memory.api.ScopedMemoryView;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import com.spaceagent.platform.runtime.api.AcceptRecoveryCommand;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.ChatApprovalResumeCommand;
import com.spaceagent.platform.runtime.api.ChatPlanResumeCommand;
import com.spaceagent.platform.runtime.api.ChatExecutionCommand;
import com.spaceagent.platform.runtime.api.ChatExecutionView;
import com.spaceagent.platform.runtime.api.ChatRecoveryCommand;
import com.spaceagent.platform.runtime.api.ChatRecoveryView;
import com.spaceagent.platform.runtime.api.ChatRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.ChatRuntimeEvent;
import com.spaceagent.platform.runtime.api.ChatToolReconciliationCommand;
import com.spaceagent.platform.runtime.api.PreparedChatExecutionCommand;
import com.spaceagent.platform.runtime.api.CompleteAgentRunCommand;
import com.spaceagent.platform.runtime.api.CompleteAgentRunFencedCommand;
import com.spaceagent.platform.runtime.api.CompleteRunStepCommand;
import com.spaceagent.platform.runtime.api.CreateCheckpointCommand;
import com.spaceagent.platform.runtime.api.FailAgentRunCommand;
import com.spaceagent.platform.runtime.api.FailAgentRunFencedCommand;
import com.spaceagent.platform.runtime.api.FailRunStepCommand;
import com.spaceagent.platform.runtime.api.RecoveryResumeStateView;
import com.spaceagent.platform.runtime.api.RecoveryView;
import com.spaceagent.platform.runtime.api.RequestRecoveryCommand;
import com.spaceagent.platform.runtime.api.RunStepView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.api.ResumeAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartRunStepCommand;
import com.spaceagent.platform.runtime.domain.RecoveryState;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshot;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshotRepository;
import com.spaceagent.platform.runtime.domain.ChatToolWaitCheckpoint;
import com.spaceagent.platform.runtime.domain.ChatPlanReviewCheckpoint;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;
import com.spaceagent.platform.runtime.api.InvokeMultiAgentOrchestrationCommand;
import com.spaceagent.platform.runtime.api.MultiAgentOrchestrationApplicationApi;
import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaimType;
import com.spaceagent.platform.runtime.domain.RuntimeOperationalTelemetry;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi.SkillCapabilityView;
import com.spaceagent.platform.runtime.api.RuntimeToolExecutionApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolReconciliationApplicationApi;
import com.spaceagent.platform.governance.api.GovernanceApprovalRequiredException;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The single execution coordinator for the platform chat HTTP path.
 */
@Service
public class ChatRuntimeApplicationService implements ChatRuntimeApplicationApi {

    private static final int MAX_APPROVAL_CHECKPOINT_CHARACTERS = 512_000;

    private final RuntimeApplicationApi runtimeApi;
    private final RuntimeCoordinationApplicationApi coordinationApi;
    private final AgentCurrentConfigurationApplicationApi currentConfigurations;
    private final ConversationApplicationApi conversationApi;
    private final TaskApplicationApi taskApi;
    private final TaskPlanApplicationApi taskPlanApi;
    private final MultiAgentOrchestrationApplicationApi multiAgentApi;
    private final ConversationContextSnapshotApplicationApi snapshotApi;
    private final ContextCompilerApplicationApi contextApi;
    private final InferenceExecutionApi inferenceApi;
    private final ModelPoolApplicationApi modelPoolApi;
    private final RuntimeToolExecutionApplicationApi toolingApi;
    private final RuntimeToolReconciliationApplicationApi reconciliationApi;
    private final MemoryApplicationApi memoryApi;
    private final KnowledgeApplicationApi knowledgeApi;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.spaceagent.platform.knowledge.api.KnowledgeCollectionRetrievalApi collectionRetrieval;
    private final RuntimeCapabilityCatalogApplicationApi capabilityCatalogApi;
    private final ObjectMapper objectMapper;
    private final RuntimeOperationalTelemetry telemetry;
    private final boolean automaticPlanningEnabled;
    private final AgentRunConfigurationSnapshotRepository runConfigurations;
    @org.springframework.beans.factory.annotation.Autowired
    private ProjectChatWorkspaceBinding projectChatWorkspace;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.spaceagent.platform.inference.api.InferenceApplicationApi modelCatalog;

    public ChatRuntimeApplicationService(
            RuntimeApplicationApi runtimeApi,
            RuntimeCoordinationApplicationApi coordinationApi,
            AgentCurrentConfigurationApplicationApi currentConfigurations,
            ConversationApplicationApi conversationApi,
            TaskApplicationApi taskApi,
            TaskPlanApplicationApi taskPlanApi,
            MultiAgentOrchestrationApplicationApi multiAgentApi,
            ConversationContextSnapshotApplicationApi snapshotApi,
            ContextCompilerApplicationApi contextApi,
            InferenceExecutionApi inferenceApi,
            ModelPoolApplicationApi modelPoolApi,
            RuntimeToolExecutionApplicationApi toolingApi,
            RuntimeToolReconciliationApplicationApi reconciliationApi,
            MemoryApplicationApi memoryApi,
            KnowledgeApplicationApi knowledgeApi,
            RuntimeCapabilityCatalogApplicationApi capabilityCatalogApi,
            ObjectMapper objectMapper,
            RuntimeOperationalTelemetry telemetry,
            AgentRunConfigurationSnapshotRepository runConfigurations,
            @Value("${platform.chat.automatic-planning-enabled:false}")
            boolean automaticPlanningEnabled) {
        this.runtimeApi = runtimeApi;
        this.coordinationApi = coordinationApi;
        this.currentConfigurations = currentConfigurations;
        this.conversationApi = conversationApi;
        this.taskApi = taskApi;
        this.taskPlanApi = taskPlanApi;
        this.multiAgentApi = multiAgentApi;
        this.snapshotApi = snapshotApi;
        this.contextApi = contextApi;
        this.inferenceApi = inferenceApi;
        this.modelPoolApi = modelPoolApi;
        this.toolingApi = toolingApi;
        this.reconciliationApi = reconciliationApi;
        this.memoryApi = memoryApi;
        this.knowledgeApi = knowledgeApi;
        this.capabilityCatalogApi = capabilityCatalogApi;
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
        this.runConfigurations = runConfigurations;
        this.automaticPlanningEnabled = automaticPlanningEnabled;
    }

    @Override
    public ChatExecutionView execute(ChatExecutionCommand command) {
        return execute(command, null);
    }

    @Override
    public ChatExecutionView executeStreaming(
            ChatExecutionCommand command,
            ChatStreamObserver observer) {
        return execute(command, observer);
    }

    private ChatExecutionView execute(
            ChatExecutionCommand command,
            ChatStreamObserver observer) {
        var currentConfiguration = currentConfigurations.requireCurrent(
                command.tenantId(), command.userId(), command.agentId());
        ConversationView conversation = resolveConversation(command);
        if(projectChatWorkspace!=null)projectChatWorkspace.requireActiveDirectory(conversation);
        var repositoryBinding = command.workspaceId() == null ? null : projectChatWorkspace.validate(
                command.tenantId(), command.userId(), conversation, command.workspaceId());
        if (repositoryBinding != null && !currentConfiguration.enabledToolIds().containsAll(
                ProjectChatWorkspaceBinding.READ_TOOLS)) {
            throw new BusinessException("Enable file_list and file_read on the Agent before starting repository chat",
                    HttpStatus.CONFLICT, "PROJECT_CHAT_READ_TOOLS_REQUIRED");
        }
        ReservedReplyView reservedReply = conversationApi.reserveReply(
                command.tenantId(), command.userId(), conversation.id(), command.message().trim());
        TaskView chatTask = createChatRootTask(command, conversation, reservedReply);
        if (chatTask != null) {
            conversation = conversationApi.setActiveTask(
                    new com.spaceagent.platform.conversation.api.SetConversationActiveTaskCommand(
                            command.tenantId(), command.userId(), conversation.id(), chatTask.id()));
        }
        AgentRunView run;
        try {
            run = runtimeApi.startRun(new StartAgentRunCommand(
                    command.tenantId(), command.userId(), currentConfiguration.agentId(),
                    null, conversation.id(),
                    chatTask == null ? null : chatTask.id(),
                    null, null, null, null, null, null));
            if (repositoryBinding != null) projectChatWorkspace.pin(run, repositoryBinding);
        } catch (RuntimeException error) {
            failChatTask(command.tenantId(), command.userId(), conversation.id(),
                    chatTask == null ? null : chatTask.id());
            throw error;
        }
        AgentExecutionConfigurationView agent;
        ModelSelection modelSelection;
        try {
            agent = configuration(run);
            runtimeApi.markRunInProgress(run.id());
            if (observer != null) observer.onRuntimeEvent(event("run_accepted",Map.of("agentRunId",run.id(),"conversationId",conversation.id())));
            modelSelection = resolveModelSelection(command, agent, run.id());
        }
        catch (RuntimeException error) {
            runtimeApi.fail(new FailAgentRunCommand(run.id(),abbreviate(error.getMessage(),500)));
            failChatTask(command.tenantId(),command.userId(),conversation.id(),chatTask==null?null:chatTask.id());
            throw error;
        }
        try (RuntimeOperationalTelemetry.InvocationSpan span = telemetry.startAgent(
                Long.toString(currentConfiguration.agentRevision()), "spaceagent.chat")) {
            try {
                ChatExecutionView planningWait = beginAutomaticPlanning(
                        command, agent, conversation, run, reservedReply,
                        chatTask == null ? null : chatTask.id(), observer);
                if (planningWait != null) {
                    span.success();
                    return planningWait;
                }
                ChatExecutionView result = executeRun(
                        command, agent, modelSelection, conversation, run, true, observer,
                        reservedReply, chatTask == null ? null : chatTask.id());
                span.success();
                return result;
            } catch (RuntimeException error) {
                span.error(error instanceof BusinessException business
                        ? business.getCode() : "agent_execution_error");
                throw error;
            }
        }
    }

    @Override
    public ChatExecutionView executePrepared(PreparedChatExecutionCommand command) {
        AgentRunView run = runtimeApi.findRun(command.agentRunId())
                .filter(value -> command.tenantId().equals(value.tenantId()))
                .filter(value -> command.userId().equals(value.ownerId()))
                .filter(value -> command.agentId().equals(value.agentId()))
                .filter(value -> Objects.equals(
                        command.configurationSnapshotId(), value.configurationSnapshotId()))
                .filter(value -> command.conversationId().equals(value.conversationId()))
                .orElseThrow(() -> new BusinessException(
                        "Prepared AgentRun scope mismatch", HttpStatus.CONFLICT,
                        "PREPARED_CHAT_RUN_MISMATCH"));
        if (run.state() != com.spaceagent.platform.runtime.domain.AgentRunState.IN_PROGRESS) {
            throw new BusinessException(
                    "Prepared AgentRun must be fenced and IN_PROGRESS",
                    HttpStatus.CONFLICT,
                    "PREPARED_CHAT_RUN_NOT_FENCED");
        }
        AgentExecutionConfigurationView agent = configuration(run);
        if (!command.agentId().equals(agent.agentId())) {
            throw new BusinessException(
                    "Prepared configuration snapshot does not belong to Agent",
                    HttpStatus.CONFLICT,
                    "AGENT_CONFIGURATION_MISMATCH");
        }
        ChatExecutionCommand chat = new ChatExecutionCommand(
                command.tenantId(), command.userId(), command.conversationId(),
                command.agentId(), command.message(), null, List.of());
        ConversationView conversation = resolveConversation(chat);
        try (RuntimeOperationalTelemetry.InvocationSpan span = telemetry.startAgent(
                Long.toString(agent.sourceAgentRevision()), "spaceagent.automation")) {
            try {
                ChatExecutionView result = executeRun(
                        chat, agent, resolveModelSelection(chat, agent, run.id()), conversation, run,
                        false, null, null, run.chatTaskId());
                span.success();
                return result;
            } catch (RuntimeException error) {
                span.error(error instanceof BusinessException business
                        ? business.getCode() : "agent_execution_error");
                throw error;
            }
        }
    }

    @Override
    public ChatExecutionView resumeApproval(ChatApprovalResumeCommand command) {
        AgentRunView run = runtimeApi.findRun(command.agentRunId())
                .filter(value -> command.tenantId().equals(value.tenantId()))
                .filter(value -> command.userId().equals(value.ownerId()))
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found", HttpStatus.NOT_FOUND, "CHAT_RUN_NOT_FOUND"));
        if (run.state() != AgentRunState.WAITING_FOR_USER
                && run.state() != AgentRunState.WAITING_FOR_TOOL
                && run.state() != AgentRunState.IN_PROGRESS) {
            throw new BusinessException(
                    "Chat Run is not waiting for approval", HttpStatus.CONFLICT,
                    "CHAT_APPROVAL_RESUME_STATE_CONFLICT");
        }
        ChatToolWaitCheckpoint checkpoint = approvalCheckpoint(run.id());
        if (!ChatToolWaitCheckpoint.APPROVAL.equals(checkpoint.waitKind())) {
            throw new BusinessException(
                    "Chat Run is waiting for Tool reconciliation, not approval",
                    HttpStatus.CONFLICT, "CHAT_APPROVAL_RESUME_STATE_CONFLICT");
        }
        if (!Objects.equals(run.tenantId(), checkpoint.tenantId())
                || !Objects.equals(run.ownerId(), checkpoint.ownerId())
                || !Objects.equals(run.agentId(), checkpoint.agentId())
                || !Objects.equals(run.configurationSnapshotId(), checkpoint.runConfigurationSnapshotId())
                || !Objects.equals(run.conversationId(), checkpoint.conversationId())) {
            throw new BusinessException(
                    "Chat approval checkpoint scope does not match the Run",
                    HttpStatus.CONFLICT, "CHAT_APPROVAL_CHECKPOINT_SCOPE_MISMATCH");
        }
        if (!command.approvalId().equals(checkpoint.pendingApprovalId())) {
            throw new BusinessException(
                    "Approval does not match the pending Chat Tool", HttpStatus.CONFLICT,
                    "CHAT_APPROVAL_MISMATCH");
        }

        String leaseOwner = "chat-resume-" + UUID.randomUUID();
        var claim = coordinationApi.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), leaseOwner, 300));
        if (claim.type() != RunWorkerLeaseClaimType.ACQUIRED) {
            throw new BusinessException(
                    "Chat approval resume is already in progress", HttpStatus.CONFLICT,
                    "CHAT_APPROVAL_RESUME_BUSY");
        }
        var lease = claim.lease();
        try {
            runtimeApi.resumeFenced(new ResumeAgentRunCommand(
                    run.id(), lease.leaseToken(), lease.fencingToken()));
            ChatExecutionView result = resumeFromCheckpoint(checkpoint, command.approvalId());
            if (!result.waiting()) {
                runtimeApi.completeFenced(new CompleteAgentRunFencedCommand(
                        run.id(), lease.leaseToken(), lease.fencingToken()));
            }
            return result;
        } catch (RuntimeException error) {
            if (!(error instanceof GovernanceApprovalRequiredException)) {
                try {
                    runtimeApi.failFenced(new FailAgentRunFencedCommand(
                            run.id(), lease.leaseToken(), lease.fencingToken(),
                            abbreviate(error.getMessage(), 500)));
                } catch (RuntimeException ignored) {
                    // Preserve the original resume failure or a newer fenced state.
                }
                failChatTask(
                        checkpoint.tenantId(), checkpoint.ownerId(), checkpoint.conversationId(),
                        checkpoint.chatTaskId());
                cancelCheckpointPlan(checkpoint);
            }
            throw error;
        } finally {
            try {
                coordinationApi.releaseLease(new RuntimeCoordinationApplicationApi.ReleaseLeaseCommand(
                        run.id(), leaseOwner, lease.leaseToken(), lease.fencingToken()));
            } catch (RuntimeException ignored) {
                // Terminal state or lease expiry remains the recovery backstop.
            }
        }
    }

    @Override
    public ChatExecutionView resumePlan(ChatPlanResumeCommand command) {
        AgentRunView run = runtimeApi.findRun(command.agentRunId())
                .filter(value -> command.tenantId().equals(value.tenantId()))
                .filter(value -> command.userId().equals(value.ownerId()))
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found", HttpStatus.NOT_FOUND, "CHAT_RUN_NOT_FOUND"));
        if (run.state() != AgentRunState.WAITING_FOR_USER) {
            throw new BusinessException(
                    "Chat Run is not waiting for plan review", HttpStatus.CONFLICT,
                    "CHAT_PLAN_RESUME_STATE_CONFLICT");
        }
        requireLatestCheckpointPhase(run.id(), "chat-plan-review",
                "CHAT_PLAN_RESUME_STATE_CONFLICT");
        ChatPlanReviewCheckpoint review = planReviewCheckpoint(run.id());
        if (!command.taskPlanId().equals(review.taskPlanId())
                || !Objects.equals(run.chatTaskId(), review.chatTaskId())
                || !Objects.equals(run.configurationSnapshotId(), review.runConfigurationSnapshotId())) {
            throw new BusinessException(
                    "Chat plan review checkpoint does not match the Run",
                    HttpStatus.CONFLICT, "CHAT_PLAN_RESUME_SCOPE_MISMATCH");
        }
        TaskPlanView plan = taskPlanApi.getChatPlan(new com.spaceagent.platform.project.api.GetChatTaskPlanQuery(
                command.tenantId(), command.userId(), review.conversationId(),
                review.chatTaskId(), review.taskPlanId()));
        if (plan.status() != com.spaceagent.platform.project.domain.TaskPlanStatus.ACTIVE) {
            throw new BusinessException(
                    "Chat TaskPlan must be APPROVED and ACTIVE before resume",
                    HttpStatus.CONFLICT, "CHAT_TASK_PLAN_NOT_ACTIVE");
        }
        String leaseOwner = "chat-plan-resume-" + UUID.randomUUID();
        var claim = coordinationApi.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(run.id(), leaseOwner, 300));
        if (claim.type() != RunWorkerLeaseClaimType.ACQUIRED) {
            throw new BusinessException(
                    "Chat plan resume is already in progress", HttpStatus.CONFLICT,
                    "CHAT_PLAN_RESUME_BUSY");
        }
        var lease = claim.lease();
        try {
            runtimeApi.resumeFenced(new ResumeAgentRunCommand(
                    run.id(), lease.leaseToken(), lease.fencingToken()));
            ChatExecutionView result = executeActivePlan(review, plan);
            if (!result.waiting()) {
                runtimeApi.completeFenced(new CompleteAgentRunFencedCommand(
                        run.id(), lease.leaseToken(), lease.fencingToken()));
            }
            return result;
        } catch (RuntimeException error) {
            try {
                runtimeApi.failFenced(new FailAgentRunFencedCommand(
                        run.id(), lease.leaseToken(), lease.fencingToken(),
                        abbreviate(error.getMessage(), 500)));
            } catch (RuntimeException ignored) { }
            failChatTask(review.tenantId(), review.ownerId(), review.conversationId(),
                    review.chatTaskId());
            cancelChatPlan(review.tenantId(), review.ownerId(), review.conversationId(),
                    review.chatTaskId(), review.taskPlanId());
            throw error;
        } finally {
            try {
                coordinationApi.releaseLease(new RuntimeCoordinationApplicationApi.ReleaseLeaseCommand(
                        run.id(), leaseOwner, lease.leaseToken(), lease.fencingToken()));
            } catch (RuntimeException ignored) { }
        }
    }

    @Override
    public ChatExecutionView reconcileTool(ChatToolReconciliationCommand command) {
        AgentRunView run = runtimeApi.findRun(command.agentRunId())
                .filter(value -> command.tenantId().equals(value.tenantId()))
                .filter(value -> command.userId().equals(value.ownerId()))
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found", HttpStatus.NOT_FOUND, "CHAT_RUN_NOT_FOUND"));
        if (run.state() != AgentRunState.WAITING_FOR_USER
                && run.state() != AgentRunState.WAITING_FOR_TOOL
                && run.state() != AgentRunState.IN_PROGRESS) {
            throw new BusinessException(
                    "Chat Run is not waiting for Tool reconciliation",
                    HttpStatus.CONFLICT, "CHAT_TOOL_RECONCILIATION_STATE_CONFLICT");
        }
        ChatToolWaitCheckpoint checkpoint = unknownCheckpoint(run.id());
        if (!ChatToolWaitCheckpoint.UNKNOWN.equals(checkpoint.waitKind())
                || !Objects.equals(command.toolCallId(),
                        checkpoint.toolCalls().get(checkpoint.nextToolIndex()).id())
                || !Objects.equals(command.expectedRevision(), checkpoint.pendingToolRevision())) {
            throw new BusinessException(
                    "Tool reconciliation does not match the pending Chat Tool",
                    HttpStatus.CONFLICT, "CHAT_TOOL_RECONCILIATION_MISMATCH");
        }
        if (!Objects.equals(run.tenantId(), checkpoint.tenantId())
                || !Objects.equals(run.ownerId(), checkpoint.ownerId())
                || !Objects.equals(run.agentId(), checkpoint.agentId())
                || !Objects.equals(run.configurationSnapshotId(), checkpoint.runConfigurationSnapshotId())
                || !Objects.equals(run.conversationId(), checkpoint.conversationId())) {
            throw new BusinessException(
                    "Chat Tool-wait checkpoint scope does not match the Run",
                    HttpStatus.CONFLICT, "CHAT_TOOL_UNKNOWN_CHECKPOINT_SCOPE_MISMATCH");
        }

        String leaseOwner = "chat-reconcile-" + UUID.randomUUID();
        var claim = coordinationApi.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), leaseOwner, 300));
        if (claim.type() != RunWorkerLeaseClaimType.ACQUIRED) {
            throw new BusinessException(
                    "Chat Tool reconciliation is already in progress", HttpStatus.CONFLICT,
                    "CHAT_TOOL_RECONCILIATION_BUSY");
        }
        var lease = claim.lease();
        boolean resumed = false;
        try {
            reconciliationApi.reconcile(
                    new RuntimeToolReconciliationApplicationApi.ReconcileCommand(
                            command.tenantId(), command.userId(), run.id(),
                            command.toolCallId(), command.expectedRevision(), command.reason()));
            runtimeApi.resumeFenced(new ResumeAgentRunCommand(
                    run.id(), lease.leaseToken(), lease.fencingToken()));
            resumed = true;
            ChatExecutionView result = resumeFromCheckpoint(checkpoint, null);
            if (!result.waiting()) {
                runtimeApi.completeFenced(new CompleteAgentRunFencedCommand(
                        run.id(), lease.leaseToken(), lease.fencingToken()));
            }
            return result;
        } catch (RuntimeException error) {
            if (resumed) {
                try {
                    runtimeApi.failFenced(new FailAgentRunFencedCommand(
                            run.id(), lease.leaseToken(), lease.fencingToken(),
                            abbreviate(error.getMessage(), 500)));
                } catch (RuntimeException ignored) {
                    // Preserve the original reconciliation continuation failure.
                }
                failChatTask(
                        checkpoint.tenantId(), checkpoint.ownerId(), checkpoint.conversationId(),
                        checkpoint.chatTaskId());
                cancelCheckpointPlan(checkpoint);
            }
            throw error;
        } finally {
            try {
                coordinationApi.releaseLease(new RuntimeCoordinationApplicationApi.ReleaseLeaseCommand(
                        run.id(), leaseOwner, lease.leaseToken(), lease.fencingToken()));
            } catch (RuntimeException ignored) {
                // Terminal state or lease expiry remains the recovery backstop.
            }
        }
    }

    private ChatExecutionView executeRun(
            ChatExecutionCommand command,
            AgentExecutionConfigurationView agent,
            ModelSelection modelSelection,
            ConversationView conversation,
            AgentRunView run,
            boolean ownRunLifecycle,
            ChatStreamObserver observer,
            ReservedReplyView existingReservation,
            String chatTaskId) {
        RunStepView chatStep = runtimeApi.startStep(new StartRunStepCommand(run.id(), "chat-runtime"));
        List<ChatRuntimeEvent> events = new ArrayList<>();
        addEvent(events, observer, event(
                "runtime_started", Map.of("agentRunId", run.id(), "stepId", chatStep.id())));

        ChatReplyProgress replyProgress = null;
        try {
            Map<String, Object> requestEvidence = new LinkedHashMap<>();
            requestEvidence.put("conversationId", conversation.id());
            requestEvidence.put("agentId", agent.agentId());
            requestEvidence.put("agentConfigurationHash", agent.configHash());
            requestEvidence.putAll(modelSelection.evidence());
            checkpoint(run.id(), chatStep.id(), "request-received", requestEvidence);
            addEvent(events, observer, event("model_selected", modelSelection.evidence()));
            var reservedReply = existingReservation == null
                    ? conversationApi.reserveReply(
                            command.tenantId(), command.userId(), conversation.id(),
                            command.message().trim())
                    : existingReservation;
            int userSequence = reservedReply.userMessage().sequence();
            replyProgress = new ChatReplyProgress(conversationApi,command.tenantId(),command.userId(),
                    conversation.id(),reservedReply.assistantReservationId(),observer);
            observer = replyProgress;
            checkpoint(run.id(), chatStep.id(), "message-persisted", Map.of("sequence", userSequence,
                    "assistantReservationId",reservedReply.assistantReservationId()));

            List<ScopedMemoryView> recalled = agent.memoryEnabled() ? memoryApi.recall(new MemoryRecallCommand(
                    MemoryScopeRef.user(command.userId()), List.of(), 10)) : List.of();
            KnowledgeRetrievalView knowledge = retrieveKnowledge(command.tenantId(),command.userId(),run.id(), agent, command.message());
            checkpoint(run.id(),chatStep.id(),"knowledge-retrieved",Map.of("matches",knowledge.matches().size(),"partial",knowledge.partial(),"degraded",knowledge.degraded(),"warnings",knowledge.warnings(),"stageMillis",knowledge.stageMillis()));
            CompiledChatContext compiled = compileContext(
                    command, agent, conversation, run.id(), recalled, knowledge);
            ContextPackage context = compiled.context();
            checkpointSkillUse(run.id(), chatStep.id(), compiled.skills());
            checkpoint(run.id(), chatStep.id(), "context-compiled", Map.of(
                    "contextPackageId", context.id(), "usedTokens", context.usedTokens()));

            List<InferenceExecutionApi.InferenceMessage> messages =
                    inferenceMessages(context, command.message());
            InferenceExecutionApi.InferenceExecutionResult inference = executeInferenceRound(
                    command, agent, modelSelection, run, chatStep.id(), messages,
                    agent.enabledToolIds(), 0, observer);
            addEvent(events, observer, event("inference_completed", Map.of(
                    "round", 0,
                    "inputTokens", inference.inputTokens(),
                    "outputTokens", inference.outputTokens())));
            checkpoint(run.id(), chatStep.id(), "inference-completed", Map.of(
                    "toolCallCount", inference.toolCalls().size()));

            List<String> citations = knowledge.matches().stream()
                    .map(match -> match.documentId() + "#" + match.chunkId())
                    .toList();
            List<String> recalledValues = recalled.stream().map(ScopedMemoryView::value).toList();
            List<String> toolResults = new ArrayList<>();
            RepositoryReadContext repositoryHistory = projectChatWorkspace != null && projectChatWorkspace.find(run).isPresent()
                    ? new RepositoryReadContext(objectMapper) : null;
            if (repositoryHistory != null) repositoryHistory.assistantTurn(inference.content());
            ToolProgress toolProgress = executeTools(
                    run, inference.toolCalls(), 0, null, null, toolResults, events, observer,
                    (toolIndex, toolStepId, waitKind, approvalId, toolRevision) -> approvalCheckpoint(
                            command, agent, modelSelection, conversation, chatStep,
                            reservedReply.assistantReservationId(),
                            reservedReply.assistantSequence(), chatTaskId, messages, inference,
                            toolIndex, toolResults, recalledValues, citations,
                            toolStepId, approvalId, toolRevision, waitKind), repositoryHistory);
            if (toolProgress.waiting()) {
                return waitingView(
                        run.id(), conversation.id(), inference, recalledValues, citations,
                        toolProgress, events, chatTaskId);
            }
            if (inference.toolCalls().isEmpty()) {
                checkpoint(run.id(), chatStep.id(), "tooling-skipped", Map.of());
            }
            InferenceExecutionApi.InferenceExecutionResult finalInference = inference;
            if (repositoryHistory != null && !inference.toolCalls().isEmpty()) {
                finalInference = completeRepositoryRead(command, agent, modelSelection, run, chatStep.id(),
                        messages, toolResults, events, observer, repositoryHistory);
            } else if (inference.toolCalls().isEmpty() && outputLimited(inference)) {
                finalInference = continueRepositoryAnswer(command, agent, modelSelection, run, chatStep.id(),
                        messages, inference, 9, observer);
            } else if (!toolResults.isEmpty()) {
                List<InferenceExecutionApi.InferenceMessage> followUp = new ArrayList<>(messages);
                followUp.add(new InferenceExecutionApi.InferenceMessage(
                        "user", toolEvidence(command.message(), toolResults)));
                addEvent(events, observer, event("tool_synthesis_started", Map.of(
                        "toolResultCount", toolResults.size())));
                if (observer != null && !inference.reasoningContent().isBlank()) {
                    observer.onReasoningDelta("\n\n");
                }
                finalInference = executeInferenceRound(
                        command, agent, modelSelection, run, chatStep.id(), followUp,
                        List.of(), 1, observer);
                addEvent(events, observer, event("inference_completed", Map.of(
                        "round", 1,
                        "inputTokens", finalInference.inputTokens(),
                        "outputTokens", finalInference.outputTokens())));
                checkpoint(run.id(), chatStep.id(), "tool-synthesis-completed", Map.of(
                        "toolResultCount", toolResults.size()));
            }
            if (outputLimited(finalInference) && !inference.toolCalls().isEmpty() && repositoryHistory == null) {
                var continuationMessages=new ArrayList<>(messages);
                continuationMessages.add(new InferenceExecutionApi.InferenceMessage("user",toolEvidence(command.message(),toolResults)));
                var continued=continueRepositoryAnswer(command,agent,modelSelection,run,chatStep.id(),continuationMessages,finalInference,9,observer);
                finalInference=new InferenceExecutionApi.InferenceExecutionResult(continued.content(),
                        finalInference.inputTokens()+continued.inputTokens(),finalInference.outputTokens()+continued.outputTokens(),
                        List.of(),continued.finishReason(),continued.providerRequestId(),continued.usage(),continued.selectedProviderId(),
                        continued.selectedModelId(),continued.attemptCount(),continued.candidateSnapshotHash(),
                        joinReasoning(finalInference.reasoningContent(),continued.reasoningContent()));
            }
            String assistantMessage = finalAnswer(finalInference.content());
            String reasoningContent = joinReasoning(
                    inference.reasoningContent(),
                    finalInference == inference ? "" : finalInference.reasoningContent());
            int assistantSequence = reservedReply.assistantSequence();
            if (outputLimited(finalInference)) conversationApi.updateReplyDraft(command.tenantId(),command.userId(),
                    conversation.id(),reservedReply.assistantReservationId(),assistantMessage);
            else conversationApi.completeReply(
                    command.tenantId(), command.userId(), conversation.id(),
                    reservedReply.assistantReservationId(), assistantMessage);

            ConversationContextSnapshotView snapshot = snapshotApi.save(new SaveConversationContextSnapshotCommand(
                    conversation.id(),
                    Math.max(1, (assistantSequence + 1) / 2),
                    abbreviate(assistantMessage, 2000),
                    0,
                    assistantSequence,
                    Math.max(1, assistantMessage.length() / 4),
                    "sha256:" + sha256(conversation.id() + ":" + assistantSequence + ":" + assistantMessage)));
            checkpoint(run.id(), chatStep.id(), "conversation-persisted", Map.of(
                    "conversationSnapshotId", snapshot.id(),
                    "conversationSnapshotVersion", snapshot.version()));

            MemoryEvaluationView memory = agent.memoryEnabled() ? memoryApi.evaluateMessage(new EvaluateMessageMemoryCommand(
                    command.userId(), conversation.id(), command.message(), assistantMessage)) : MemoryEvaluationView.skipped();
            addEvent(events, observer, event(
                    "memory_evaluated", Map.of("candidateCreated", memory.candidateCreated())));
            checkpoint(run.id(), chatStep.id(), "memory-evaluated", Map.of(
                    "candidateCreated", memory.candidateCreated()));

            runtimeApi.completeStep(new CompleteRunStepCommand(run.id(), chatStep.id()));
            checkpoint(run.id(), chatStep.id(), "completed", Map.of(
                    "conversationSnapshotId", snapshot.id(),
                    "conversationSnapshotVersion", snapshot.version()));
            if (ownRunLifecycle) {
                runtimeApi.complete(new CompleteAgentRunCommand(run.id()));
            }
            transitionChatTask(command.tenantId(), command.userId(), conversation.id(),
                    chatTaskId, TaskTransition.COMPLETE);
            addEvent(events, observer, event(
                    "runtime_completed", Map.of("agentRunId", run.id())));

            return new ChatExecutionView(
                    conversation.id(), run.id(), assistantMessage,
                    memory.candidateCreated(),
                    memory.candidate() == null ? null : memory.candidate().id(),
                    recalled.size(),
                    recalledValues,
                    !knowledge.matches().isEmpty(),
                    knowledge.matches().size(),
                    citations,
                    inference.inputTokens() + (finalInference == inference ? 0 : finalInference.inputTokens()),
                    inference.outputTokens() + (finalInference == inference ? 0 : finalInference.outputTokens()),
                    events,
                    reasoningContent, "COMPLETED", null, null, null, null,
                    chatTaskId, chatTaskId == null ? null : "COMPLETED");
        } catch (RuntimeException exception) {
            if (replyProgress != null) {
                try { replyProgress.flush(); } catch (RuntimeException ignored) { /* Preserve the original failure. */ }
            }
            if (runtimeApi.findRun(run.id()).map(value->value.state()==AgentRunState.CANCELLED).orElse(false)) {
                throw new BusinessException("Chat Run was cancelled",HttpStatus.CONFLICT,"CHAT_RUN_CANCELLED");
            }
            failRun(run.id(), chatStep.id(), exception, ownRunLifecycle);
            failChatTask(command.tenantId(), command.userId(), conversation.id(), chatTaskId);
            throw exception;
        }
    }

    private ChatExecutionView executeActivePlan(
            ChatPlanReviewCheckpoint review, TaskPlanView initialPlan) {
        return executeActivePlan(review, initialPlan, List.of(), null);
    }

    private ChatExecutionView executeActivePlan(
            ChatPlanReviewCheckpoint review, TaskPlanView initialPlan,
            List<ChatToolWaitCheckpoint.StepResult> seedResults,
            ModelSelection pinnedSelection) {
        AgentRunView run = runtimeApi.findRun(review.agentRunId()).orElseThrow();
        AgentExecutionConfigurationView agent = configuration(run);
        ConversationView conversation = conversationApi.find(review.conversationId())
                .filter(value -> review.tenantId().equals(value.tenantId()))
                .filter(value -> review.ownerId().equals(value.userId()))
                .orElseThrow(() -> new BusinessException(
                        "Conversation not found", HttpStatus.NOT_FOUND));
        ChatExecutionCommand command = new ChatExecutionCommand(
                review.tenantId(), review.ownerId(), review.conversationId(),
                review.agentId(), review.userMessage(), null, List.of());
        ModelSelection modelSelection = pinnedSelection == null
                ? resolveModelSelection(command, agent, run.id()) : pinnedSelection;
        List<ScopedMemoryView> recalled = agent.memoryEnabled() ? memoryApi.recall(new MemoryRecallCommand(
                MemoryScopeRef.user(review.ownerId()), List.of(), 10)) : List.of();
        KnowledgeRetrievalView knowledge = retrieveKnowledge(review.tenantId(),review.ownerId(),run.id(), agent, review.userMessage());
        CompiledChatContext compiled = compileContext(
                command, agent, conversation, run.id(), recalled, knowledge);
        ContextPackage context = compiled.context();
        List<ChatToolWaitCheckpoint.StepResult> completed = new ArrayList<>(seedResults);
        List<ChatRuntimeEvent> events = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        List<com.spaceagent.platform.project.api.PlanStepView> remaining =
                new ArrayList<>(initialPlan.steps());
        java.util.Set<String> completedIds = completed.stream()
                .map(ChatToolWaitCheckpoint.StepResult::planStepId)
                .collect(java.util.stream.Collectors.toSet());
        remaining.removeIf(step -> completedIds.contains(step.id()));
        while (!remaining.isEmpty()) {
            var step = remaining.stream()
                    .filter(value -> completedIds.containsAll(value.dependencyStepIds()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(
                            "Chat TaskPlan has no dependency-ready Step",
                            HttpStatus.CONFLICT, "CHAT_TASK_PLAN_DAG_BLOCKED"));
            remaining.remove(step);
            taskPlanApi.transitionChatPlanStep(new TransitionChatPlanStepExecutionCommand(
                    review.tenantId(), review.ownerId(), review.conversationId(),
                    review.chatTaskId(), step.childTaskId(), review.taskPlanId(), step.id(),
                    PlanStepExecutionAction.START));
            RunStepView runtimeStep;
            try {
                runtimeStep = runtimeApi.startStep(new StartRunStepCommand(
                        run.id(), "chat-plan:" + step.stepKey()));
            } catch (RuntimeException error) {
                try {
                    taskPlanApi.transitionChatPlanStep(new TransitionChatPlanStepExecutionCommand(
                            review.tenantId(), review.ownerId(), review.conversationId(),
                            review.chatTaskId(), step.childTaskId(), review.taskPlanId(), step.id(),
                            PlanStepExecutionAction.FAIL));
                } catch (RuntimeException ignored) { }
                throw error;
            }
            try {
                checkpointSkillUse(run.id(), runtimeStep.id(), compiled.skills());
                List<InferenceExecutionApi.InferenceMessage> messages =
                        new ArrayList<>(inferenceMessages(context, review.userMessage()));
                messages.add(new InferenceExecutionApi.InferenceMessage(
                        "system", plannedStepPrompt(step, completed)));
                var inference = executeInferenceRound(
                        command, agent, modelSelection, run, runtimeStep.id(), messages,
                        agent.enabledToolIds(), 0, null);
                inputTokens += inference.inputTokens();
                outputTokens += inference.outputTokens();
                List<String> toolResults = new ArrayList<>();
                ToolProgress progress = executeTools(
                        run, inference.toolCalls(), 0, null, null, toolResults, events, null,
                        (toolIndex, toolStepId, waitKind, approvalId, toolRevision) ->
                                withPlanProgress(approvalCheckpoint(
                                        command, agent, modelSelection, conversation, runtimeStep,
                                        review.assistantReservationId(), review.assistantSequence(),
                                        review.chatTaskId(), messages, inference, toolIndex,
                                        toolResults, recalled.stream().map(ScopedMemoryView::value).toList(),
                                        knowledge.matches().stream().map(match ->
                                                match.documentId() + "#" + match.chunkId()).toList(),
                                        toolStepId, approvalId, toolRevision, waitKind),
                                        review.taskPlanId(), step.id(), completed));
                if (progress.waiting()) {
                    return withPlan(waitingView(
                            run.id(), conversation.id(), inference,
                            recalled.stream().map(ScopedMemoryView::value).toList(),
                            knowledge.matches().stream().map(match ->
                                    match.documentId() + "#" + match.chunkId()).toList(),
                            progress, events, review.chatTaskId()), initialPlan);
                }
                var finalInference = inference;
                if (!toolResults.isEmpty()) {
                    List<InferenceExecutionApi.InferenceMessage> followUp = new ArrayList<>(messages);
                    followUp.add(new InferenceExecutionApi.InferenceMessage(
                            "user", toolEvidence(step.expectedOutput(), toolResults)));
                    finalInference = executeInferenceRound(
                            command, agent, modelSelection, run, runtimeStep.id(), followUp,
                            List.of(), 1, null);
                    inputTokens += finalInference.inputTokens();
                    outputTokens += finalInference.outputTokens();
                }
                completed.add(new ChatToolWaitCheckpoint.StepResult(
                        step.id(), step.stepKey(), abbreviate(finalAnswer(finalInference.content()), 8_000)));
                runtimeApi.completeStep(new CompleteRunStepCommand(run.id(), runtimeStep.id()));
                taskPlanApi.transitionChatPlanStep(new TransitionChatPlanStepExecutionCommand(
                        review.tenantId(), review.ownerId(), review.conversationId(),
                        review.chatTaskId(), step.childTaskId(), review.taskPlanId(), step.id(),
                        PlanStepExecutionAction.COMPLETE));
                completedIds.add(step.id());
                checkpoint(run.id(), runtimeStep.id(), "chat-plan-step-completed", Map.of(
                        "taskPlanId", review.taskPlanId(),
                        "completedPlanStepId", step.id(),
                        "completedSteps", List.copyOf(completed),
                        "modelSelection", toSnapshot(modelSelection)));
            } catch (RuntimeException error) {
                try {
                    runtimeApi.failStep(new FailRunStepCommand(run.id(), runtimeStep.id()));
                    taskPlanApi.transitionChatPlanStep(new TransitionChatPlanStepExecutionCommand(
                            review.tenantId(), review.ownerId(), review.conversationId(),
                            review.chatTaskId(), step.childTaskId(), review.taskPlanId(), step.id(),
                            PlanStepExecutionAction.FAIL));
                } catch (RuntimeException ignored) { }
                throw error;
            }
        }
        return finishPlannedChat(
                review, initialPlan, command, agent, modelSelection, conversation, run,
                context, recalled, knowledge, completed, events, inputTokens, outputTokens);
    }

    private ChatExecutionView finishPlannedChat(
            ChatPlanReviewCheckpoint review, TaskPlanView plan, ChatExecutionCommand command,
            AgentExecutionConfigurationView agent, ModelSelection modelSelection,
            ConversationView conversation, AgentRunView run, ContextPackage context,
            List<ScopedMemoryView> recalled, KnowledgeRetrievalView knowledge,
            List<ChatToolWaitCheckpoint.StepResult> completed, List<ChatRuntimeEvent> events,
            int priorInputTokens, int priorOutputTokens) {
        RunStepView synthesisStep = runtimeApi.startStep(new StartRunStepCommand(
                run.id(), "chat-plan-synthesis"));
        try {
            checkpointSkillUse(run.id(), synthesisStep.id(), resolveSkills(command, agent));
            List<InferenceExecutionApi.InferenceMessage> messages =
                    new ArrayList<>(inferenceMessages(context, review.userMessage()));
            messages.add(new InferenceExecutionApi.InferenceMessage(
                    "user", "Synthesize the final answer from these completed plan steps:\n"
                            + completed.stream().map(value ->
                                    value.stepKey() + ": " + value.content())
                            .collect(java.util.stream.Collectors.joining("\n"))));
            var inference = executeInferenceRound(
                    command, agent, modelSelection, run, synthesisStep.id(), messages,
                    List.of(), 0, null);
            inference=continuePersistedAnswer(command,agent,modelSelection,run,synthesisStep.id(),messages,inference,review.assistantReservationId());
            String answer = finalAnswer(inference.content());
            if(outputLimited(inference))conversationApi.updateReplyDraft(review.tenantId(),review.ownerId(),conversation.id(),review.assistantReservationId(),answer);
            else conversationApi.completeReply(
                    review.tenantId(), review.ownerId(), conversation.id(),
                    review.assistantReservationId(), answer);
            ConversationContextSnapshotView snapshot = snapshotApi.save(
                    new SaveConversationContextSnapshotCommand(
                            conversation.id(), Math.max(1, (review.assistantSequence() + 1) / 2),
                            abbreviate(answer, 2_000), 0, review.assistantSequence(),
                            Math.max(1, answer.length() / 4),
                            "sha256:" + sha256(conversation.id() + ":"
                                    + review.assistantSequence() + ":" + answer)));
            MemoryEvaluationView memory = agent.memoryEnabled() ? memoryApi.evaluateMessage(new EvaluateMessageMemoryCommand(
                    review.ownerId(), conversation.id(), review.userMessage(), answer)) : MemoryEvaluationView.skipped();
            runtimeApi.completeStep(new CompleteRunStepCommand(run.id(), synthesisStep.id()));
            taskPlanApi.transitionChatPlan(new ChatTaskPlanActionCommand(
                    review.tenantId(), review.ownerId(), review.conversationId(),
                    review.chatTaskId(), review.taskPlanId(), TaskPlanAction.COMPLETE));
            transitionChatTask(review.tenantId(), review.ownerId(), review.conversationId(),
                    review.chatTaskId(), TaskTransition.COMPLETE);
            checkpoint(run.id(), synthesisStep.id(), "completed", Map.of(
                    "taskPlanId", plan.id(), "completedStepCount", completed.size(),
                    "conversationSnapshotId", snapshot.id()));
            addEvent(events, null, event("runtime_completed", Map.of("agentRunId", run.id())));
            List<String> citations = knowledge.matches().stream()
                    .map(match -> match.documentId() + "#" + match.chunkId()).toList();
            return new ChatExecutionView(
                    conversation.id(), run.id(), answer, memory.candidateCreated(),
                    memory.candidate() == null ? null : memory.candidate().id(), recalled.size(),
                    recalled.stream().map(ScopedMemoryView::value).toList(), !citations.isEmpty(),
                    citations.size(), citations, priorInputTokens + inference.inputTokens(),
                    priorOutputTokens + inference.outputTokens(), events, inference.reasoningContent(),
                    "COMPLETED", null, null, null, null, review.chatTaskId(), "COMPLETED",
                    plan.id(), "COMPLETED");
        } catch (RuntimeException error) {
            try {
                runtimeApi.failStep(new FailRunStepCommand(run.id(), synthesisStep.id()));
            } catch (RuntimeException ignored) { }
            throw error;
        }
    }

    private String plannedStepPrompt(
            com.spaceagent.platform.project.api.PlanStepView step,
            List<ChatToolWaitCheckpoint.StepResult> completed) {
        String prior = completed.isEmpty() ? "none" : completed.stream()
                .map(value -> value.stepKey() + ": " + value.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        return "Execute only this approved plan step. stepKey=" + step.stepKey()
                + "\nexpectedOutput=" + step.expectedOutput()
                + "\nacceptanceCriteria=" + String.join("; ", step.acceptanceCriteria())
                + "\ncompletedStepEvidence:\n" + prior;
    }

    private ChatToolWaitCheckpoint withPlanProgress(
            ChatToolWaitCheckpoint base, String taskPlanId, String planStepId,
            List<ChatToolWaitCheckpoint.StepResult> completed) {
        return new ChatToolWaitCheckpoint(
                base.schema(), base.tenantId(), base.ownerId(), base.agentRunId(),
                base.conversationId(), base.agentId(), base.runConfigurationSnapshotId(), base.chatStepId(),
                base.assistantReservationId(), base.assistantSequence(), base.chatTaskId(),
                base.userMessage(), base.modelSelection(), base.messages(), base.toolCalls(),
                base.nextToolIndex(), base.toolResults(), base.initialContent(),
                base.initialInputTokens(), base.initialOutputTokens(),
                base.initialReasoningContent(), base.recalledMemories(), base.citations(),
                base.pendingToolStepId(), base.pendingApprovalId(), base.pendingToolRevision(),
                base.waitKind(), new ChatToolWaitCheckpoint.PlanProgress(
                        taskPlanId, planStepId, List.copyOf(completed)));
    }

    private ChatExecutionView withPlan(ChatExecutionView value, TaskPlanView plan) {
        return new ChatExecutionView(
                value.conversationId(), value.agentRunId(), value.assistantMessage(),
                value.memoryUpdated(), value.memoryCandidateId(), value.recalledMemoryCount(),
                value.recalledMemories(), value.ragUsed(), value.retrievedChunkCount(),
                value.citations(), value.inputTokenCount(), value.outputTokenCount(), value.events(),
                value.reasoningContent(), value.executionState(), value.pendingApprovalId(),
                value.pendingToolCallId(), value.pendingToolName(), value.pendingToolRevision(),
                value.rootTaskId(), value.rootTaskState(), plan.id(), plan.status().name());
    }

    @Override
    public ChatRecoveryView recover(ChatRecoveryCommand command) {
        runtimeApi.findRun(command.agentRunId())
                .filter(run -> command.tenantId().equals(run.tenantId()))
                .filter(run -> command.userId().equals(run.ownerId()))
                .orElseThrow(() -> new BusinessException("Agent run not found", HttpStatus.NOT_FOUND));
        RecoveryView recovery = runtimeApi.requestRecovery(new RequestRecoveryCommand(
                command.agentRunId(), command.reason()));
        if (recovery.state() == RecoveryState.FAILED) {
            return new ChatRecoveryView(
                    command.agentRunId(), recovery.id(), recovery.state().name(), null, false);
        }
        RecoveryResumeStateView resumeState = runtimeApi.reconstructResumeState(command.agentRunId())
                .orElseThrow(() -> new BusinessException(
                        "Unable to reconstruct chat run",
                        HttpStatus.CONFLICT));
        RecoveryView accepted = runtimeApi.acceptRecovery(new AcceptRecoveryCommand(
                command.agentRunId(), recovery.id(), resumeState));
        return new ChatRecoveryView(
                command.agentRunId(),
                accepted.id(),
                accepted.state().name(),
                resumeState.latestCheckpointId(),
                true);
    }

    private ConversationView resolveConversation(ChatExecutionCommand command) {
        if (command.conversationId() == null) {
            return conversationApi.start(new StartConversationCommand(
                    null, null, command.tenantId(), command.userId(), command.agentId(),
                    abbreviate(command.message(), 80)));
        }
        return conversationApi.find(command.conversationId())
                .filter(conversation -> command.userId().equals(conversation.userId()))
                .filter(conversation -> command.tenantId().equals(conversation.tenantId()))
                .filter(conversation -> conversation.agentId() == null
                        || command.agentId().equals(conversation.agentId()))
                .orElseThrow(() -> new BusinessException("Conversation not found", HttpStatus.NOT_FOUND));
    }

    private TaskView createChatRootTask(
            ChatExecutionCommand command,
            ConversationView conversation,
            ReservedReplyView reservation) {
        if (conversation.projectId() != null) return null;
        return taskApi.createOrGetChatRootTask(new CreateChatRootTaskCommand(
                command.tenantId(), command.userId(), conversation.id(),
                reservation.userMessage().id(), abbreviate(command.message(), 200),
                command.message().trim()));
    }

    private ChatExecutionView beginAutomaticPlanning(
            ChatExecutionCommand command,
            AgentExecutionConfigurationView agent,
            ConversationView conversation,
            AgentRunView run,
            ReservedReplyView reservation,
            String chatTaskId,
            ChatStreamObserver observer) {
        if (!automaticPlanningEnabled || chatTaskId == null || conversation.projectId() != null) {
            return null;
        }
        RunStepView planningStep = runtimeApi.startStep(new StartRunStepCommand(
                run.id(), "chat-planning"));
        List<ChatRuntimeEvent> events = new ArrayList<>();
        addEvent(events, observer, event(
                "planning_started", Map.of("agentRunId", run.id())));
        try {
            List<SkillCapabilityView> skills = resolveSkills(command, agent);
            int tokenBudget = Math.max(64, Math.min(
                    agent.maxContextTokens(), Math.max(agent.maxOutputTokens(), 2_048)));
            List<MultiAgentOrchestrationRequest.ContextSource> planningSources = new ArrayList<>();
            planningSources.add(new MultiAgentOrchestrationRequest.ContextSource(
                    "TASK", chatTaskId, abbreviate(command.message().trim(), 8_000), 100));
            for (var skill : skills) {
                String prompt = skillPrompt(skill);
                if (prompt.length() > 32_000) {
                    throw new BusinessException("Skill exceeds the Planner context-source limit; shorten its instructions",
                            HttpStatus.PAYLOAD_TOO_LARGE, "AGENT_SKILL_PLANNER_CONTEXT_TOO_LARGE");
                }
                planningSources.add(new MultiAgentOrchestrationRequest.ContextSource(
                        "SKILL", skill.id(), prompt, 95));
            }
            checkpointSkillUse(run.id(), planningStep.id(), skills);
            MultiAgentOrchestrationResponse response = multiAgentApi.invoke(
                    new InvokeMultiAgentOrchestrationCommand(
                            run.id(),
                            List.of(new MultiAgentOrchestrationRequest.AgentRef(
                                    agent.agentId(), run.id(), "supervisor")),
                            agent.modelPoolId(),
                            new MultiAgentOrchestrationRequest.Context(
                                    "chat-planning:" + run.id(), tokenBudget,
                                    planningSources),
                            new MultiAgentOrchestrationRequest.Capabilities(
                                    modelVisibleToolIds(agent, run, agent.enabledToolIds()),
                                    agent.skillIds(), List.of()),
                            new MultiAgentOrchestrationRequest.Limits(
                                    0, 1, 1, tokenBudget)));
            if (response.command().kind()
                    == MultiAgentOrchestrationResponse.CommandKind.COMPLETED) {
                runtimeApi.completeStep(new CompleteRunStepCommand(run.id(), planningStep.id()));
                runtimeApi.advanceCursor(
                        new com.spaceagent.platform.runtime.api.AdvanceExecutionCursorCommand(
                                run.id(), "chat-runtime", null));
                addEvent(events, observer, event("planning_skipped", Map.of(
                        "agentRunId", run.id(), "reason", "planner_completed")));
                return null;
            }
            if (response.command().kind()
                    != MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED) {
                throw new BusinessException(
                        "Chat Planner returned an unsupported command",
                        HttpStatus.BAD_GATEWAY, "CHAT_PLANNER_COMMAND_INVALID");
            }
            TaskPlanView plan = taskPlanApi.getChatPlanBySourceRun(
                    new GetChatTaskPlanBySourceRunQuery(
                            command.tenantId(), command.userId(), conversation.id(), run.id()));
            if (!chatTaskId.equals(plan.rootTaskId())
                    || plan.status() != com.spaceagent.platform.project.domain.TaskPlanStatus.PROPOSED) {
                throw new BusinessException(
                        "Persisted Chat TaskPlan does not match the planning Run",
                        HttpStatus.CONFLICT, "CHAT_PLANNER_PERSISTED_SCOPE_MISMATCH");
            }
            ChatPlanReviewCheckpoint review = new ChatPlanReviewCheckpoint(
                    ChatPlanReviewCheckpoint.SCHEMA,
                    command.tenantId(), command.userId(), run.id(), conversation.id(),
                    agent.agentId(), run.id(), chatTaskId, plan.id(),
                    reservation.assistantReservationId(), reservation.assistantSequence(),
                    command.message().trim());
            JsonNode reviewNode = objectMapper.valueToTree(review);
            if (reviewNode.toString().length() > MAX_APPROVAL_CHECKPOINT_CHARACTERS) {
                throw new BusinessException(
                        "Chat plan review checkpoint exceeds the recovery limit",
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "CHAT_PLAN_REVIEW_CHECKPOINT_TOO_LARGE");
            }
            checkpoint(run.id(), planningStep.id(), "chat-plan-review", Map.of(
                    "planReview", reviewNode));
            runtimeApi.completeStep(new CompleteRunStepCommand(run.id(), planningStep.id()));
            runtimeApi.markRunWaitingForUser(run.id());
            addEvent(events, observer, event("plan_approval_required", Map.of(
                    "agentRunId", run.id(), "taskPlanId", plan.id(),
                    "stepCount", plan.steps().size())));
            return new ChatExecutionView(
                    conversation.id(), run.id(), "", false, null,
                    0, List.of(), false, 0, List.of(), 0, 0, events, "",
                    "WAITING_PLAN_APPROVAL", null, null, null, null,
                    chatTaskId, "IN_PROGRESS", plan.id(), plan.status().name());
        } catch (RuntimeException error) {
            failRun(run.id(), planningStep.id(), error, true);
            failChatTask(command.tenantId(), command.userId(), conversation.id(), chatTaskId);
            throw error;
        }
    }

    private void transitionChatTask(
            String tenantId,
            String userId,
            String conversationId,
            String taskId,
            TaskTransition transition) {
        if (taskId == null) return;
        taskApi.transitionChatTask(new TransitionChatTaskCommand(
                tenantId, userId, conversationId, taskId, transition));
    }

    private void failChatTask(
            String tenantId, String userId, String conversationId, String taskId) {
        try {
            transitionChatTask(
                    tenantId, userId, conversationId, taskId, TaskTransition.FAIL);
        } catch (RuntimeException ignored) {
            // Preserve the originating Runtime failure; terminal Task evidence remains authoritative.
        }
    }

    private void cancelCheckpointPlan(ChatToolWaitCheckpoint checkpoint) {
        if (checkpoint.planProgress() == null) return;
        cancelChatPlan(
                checkpoint.tenantId(), checkpoint.ownerId(), checkpoint.conversationId(),
                checkpoint.chatTaskId(), checkpoint.planProgress().taskPlanId());
    }

    private void cancelChatPlan(
            String tenantId, String userId, String conversationId,
            String rootTaskId, String taskPlanId) {
        try {
            taskPlanApi.transitionChatPlan(new ChatTaskPlanActionCommand(
                    tenantId, userId, conversationId, rootTaskId, taskPlanId,
                    TaskPlanAction.CANCEL));
        } catch (RuntimeException ignored) {
            // Preserve the originating Runtime failure and any newer terminal plan state.
        }
    }

    private ModelSelection resolveModelSelection(
            ChatExecutionCommand command,
            AgentExecutionConfigurationView agent,
            String routingKey) {
        if (agent.modelPoolId() != null) {
            if (command.modelId() != null && !command.modelId().isBlank()) {
                throw new BusinessException(
                        "A model override cannot bypass an Agent ModelPool",
                        HttpStatus.BAD_REQUEST,
                        "MODEL_POOL_OVERRIDE_NOT_ALLOWED");
            }
            ModelPoolResolutionView resolution = modelPoolApi.resolvePool(
                    command.tenantId(), command.userId(), agent.modelPoolId(),routingKey);
            ResolvedModelCandidateView primary = resolution.candidates().getFirst();
            return new ModelSelection(
                    agent.modelPoolId(),resolution.candidates().stream().map(v->
                    new InferenceExecutionApi.InferenceCandidate(v.memberId(),v.providerId(),
                    v.providerModelId(),v.modelId(),v.priority(),v.weight(),v.healthLatencyMs(),
                    v.priceId(),v.inputMicrosPerMillionTokens(),v.outputMicrosPerMillionTokens())).toList(),
                    resolution.routingStrategy().name(),resolution.fallbackEnabled(),
                    resolution.candidateSnapshotHash());
        }
        return new ModelSelection(
                null,List.of(new InferenceExecutionApi.InferenceCandidate(null,
                agent.modelProviderId()==null?"generic":agent.modelProviderId(),null,
                command.modelId()==null?(agent.modelId()==null?"default":agent.modelId()):command.modelId(),
                0,1,null,null,null,null)),"PRIORITY",false,null);
    }

    private CompiledChatContext compileContext(
            ChatExecutionCommand command,
            AgentExecutionConfigurationView agent,
            ConversationView conversation,
            String runId,
            List<ScopedMemoryView> memories,
            KnowledgeRetrievalView knowledge) {
        List<CompileContextCommand.ContextContribution> contributions = new ArrayList<>();
        List<SkillCapabilityView> skills = resolveSkills(command, agent);
        if (agent.systemPrompt() != null && !agent.systemPrompt().isBlank()) {
            contributions.add(contribution(
                    ContextSourceType.SYSTEM, agent.agentId(), agent.systemPrompt(), 100, 1.0));
        }
        skills.forEach(skill -> contributions.add(contribution(
                ContextSourceType.SKILL, skill.id(), skillPrompt(skill), 95, 1.0)));
        String history = conversationApi.recentMessages(conversation.id(), 100).stream()
                .map(message -> message.role() + ": " + message.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (history.isBlank()) history = command.message();
        contributions.add(contribution(
                ContextSourceType.CONVERSATION, conversation.id(), history, 90, 1.0));
        if (agent.memoryEnabled()) memories.forEach(memory -> contributions.add(contribution(
                ContextSourceType.MEMORY, memory.id(), memory.value(), 70, 0.8)));
        if (agent.ragEnabled()) knowledge.matches().forEach(match -> contributions.add(contribution(
                ContextSourceType.KNOWLEDGE, match.chunkId(), match.content(), 80, match.score())));
        ContextPackage context = contextApi.compile(new CompileContextCommand(
                command.userId(), null, null, conversation.id(), runId,
                Math.max(1024, agent.maxContextTokens() - agent.maxOutputTokens()),
                "runtime.chat",
                contributions));
        java.util.Set<String> selectedSkillIds = context.sources().stream()
                .filter(source -> source.type() == ContextSourceType.SKILL)
                .map(ContextSource::sourceId)
                .collect(java.util.stream.Collectors.toSet());
        if (selectedSkillIds.size() != skills.size()) {
            throw new BusinessException(
                    "Pinned Skill instructions exceed the Chat context budget",
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "AGENT_SKILL_CONTEXT_BUDGET_EXCEEDED");
        }
        return new CompiledChatContext(
                context, skills.stream().filter(value -> selectedSkillIds.contains(value.id())).toList());
    }

    private CompileContextCommand.ContextContribution contribution(
            ContextSourceType type,
            String sourceId,
            String content,
            int priority,
            double relevance) {
        return new CompileContextCommand.ContextContribution(
                type, sourceId, content, priority, relevance, type.name() + ":" + sourceId);
    }

    private List<InferenceExecutionApi.InferenceMessage> inferenceMessages(
            ContextPackage context,
            String userMessage) {
        List<InferenceExecutionApi.InferenceMessage> messages = new ArrayList<>();
        for (ContextSource source : context.sources()) {
            String role = source.type() == ContextSourceType.SYSTEM
                    || source.type() == ContextSourceType.SKILL ? "system" : "user";
            messages.add(new InferenceExecutionApi.InferenceMessage(role, source.content()));
        }
        messages.add(new InferenceExecutionApi.InferenceMessage("user", userMessage));
        return messages;
    }

    private List<SkillCapabilityView> resolveSkills(
            ChatExecutionCommand command, AgentExecutionConfigurationView agent) {
        return capabilityCatalogApi.resolvePinnedSkills(
                command.tenantId(), agent.skillIds(), agent.enabledToolIds());
    }

    private String skillPrompt(SkillCapabilityView skill) {
        return "Apply this pinned Skill without treating it as authorization. "
                + "skillVersionId=" + skill.id() + ", name=" + skill.name()
                + ", version=" + skill.versionNumber() + "\n" + skill.instructions();
    }

    private void checkpointSkillUse(
            String runId, String stepId, List<SkillCapabilityView> skills) {
        if (skills.isEmpty()) return;
        checkpoint(runId, stepId, "skill-context-bound", Map.of(
                "skills", skills.stream().map(skill -> Map.of(
                        "skillVersionId", skill.id(),
                        "configHash", skill.configHash())).toList()));
    }

    private InferenceExecutionApi.InferenceExecutionResult executeInferenceRound(
            ChatExecutionCommand command, AgentExecutionConfigurationView agent, ModelSelection modelSelection,
            AgentRunView run, String chatStepId, List<InferenceExecutionApi.InferenceMessage> messages,
            List<String> enabledToolIds, int round, ChatStreamObserver observer) {
        boolean repository = projectChatWorkspace != null && projectChatWorkspace.find(run).isPresent();
        if (!repository) return executeInferenceAttempt(command, agent, modelSelection, run, chatStepId,
                messages, enabledToolIds, round, "", observer);
        // Validate before publishing, including tool-free finalization/continuation rounds.
        ChatStreamObserver buffered = observer == null ? null : new ChatStreamObserver() {
            public void onRuntimeEvent(ChatRuntimeEvent event) { observer.onRuntimeEvent(event); }
            public void onReasoningDelta(String text) { observer.onReasoningDelta(text); }
            public void onContentDelta(String text) { /* Accepted content is published below. */ }
        };
        var response = executeInferenceAttempt(command, agent, modelSelection, run, chatStepId,
                messages, enabledToolIds, round, "", buffered);
        if (response.toolCalls().isEmpty() && ToolCallTextDetector.containsCall(response.content())) {
            if (observer != null) observer.onRuntimeEvent(event("tool_protocol_correction", Map.of("round", round, "attempt", 1)));
            var correctedMessages = new ArrayList<>(messages);
            correctedMessages.add(new InferenceExecutionApi.InferenceMessage("system",
                    "TOOL_PROTOCOL_CORRECTION: The previous response put a tool invocation in answer text. "
                    + "Never emit invoke/function_calls/tool_call markup as an action. "
                    + (enabledToolIds.isEmpty()
                        ? "No tools are available in this round. Give a final answer from existing evidence; explicitly state any limitations."
                        : "If another permitted read is needed, use the native structured tool_calls field with the supplied tool schema. "
                            + "Otherwise provide the actual final answer, not a promise to read files.")
                    + "If discussing markup as documentation, place the literal example in a fenced code block."));
            var corrected = executeInferenceAttempt(command, agent, modelSelection, run, chatStepId,
                    correctedMessages, enabledToolIds, round, ":protocol-correction", buffered);
            if (corrected.toolCalls().isEmpty() && ToolCallTextDetector.containsCall(corrected.content())) {
                throw new BusinessException("Model returned tool instructions as answer text after one correction; no textual invocation was executed",
                        HttpStatus.BAD_GATEWAY, "CHAT_TOOL_PROTOCOL_INVALID");
            }
            response = new InferenceExecutionApi.InferenceExecutionResult(corrected.content(),
                    response.inputTokens() + corrected.inputTokens(), response.outputTokens() + corrected.outputTokens(),
                    corrected.toolCalls(), corrected.finishReason(), corrected.providerRequestId(), corrected.usage(),
                    corrected.selectedProviderId(), corrected.selectedModelId(), response.attemptCount() + corrected.attemptCount(),
                    corrected.candidateSnapshotHash(), joinReasoning(response.reasoningContent(), corrected.reasoningContent()));
        }
        if (observer != null && response.toolCalls().isEmpty() && !response.content().isBlank()) observer.onContentDelta(response.content());
        return response;
    }

    private InferenceExecutionApi.InferenceExecutionResult executeInferenceAttempt(
            ChatExecutionCommand command,
            AgentExecutionConfigurationView agent,
            ModelSelection modelSelection,
            AgentRunView run,
            String chatStepId,
            List<InferenceExecutionApi.InferenceMessage> messages,
            List<String> enabledToolIds,
            int round,
            String logicalSuffix,
            ChatStreamObserver observer) {
        ensureNotCancelled(run.id());
        List<String> exposedToolIds = modelVisibleToolIds(agent, run, enabledToolIds);
        var repositoryBinding = projectChatWorkspace == null ? java.util.Optional.<ProjectChatWorkspaceBinding.Binding>empty()
                : projectChatWorkspace.find(run);
        if (repositoryBinding.isPresent()) {
            messages = new ArrayList<>(messages);
            messages.addFirst(new InferenceExecutionApi.InferenceMessage("system",
                    "The user selected a repository workspace for this conversation. Read its actual files using "
                    + "file_list and file_read with workspaceId=" + repositoryBinding.get().workspaceId()
                    + ". Start at path '.'. This attachment is read-only; do not claim repository access via knowledge_search."));
        }
        var definitions=toolDefinitions(exposedToolIds);
        messages=ChatContextBudget.fit(messages,command.message(),contextWindow(agent,modelSelection),agent.maxOutputTokens(),
                objectMapper.valueToTree(definitions).toString());
        InferenceExecutionApi.InferenceExecutionCommand inferenceCommand =
                new InferenceExecutionApi.InferenceExecutionCommand(
                        modelSelection.providerId(), modelSelection.modelId(), messages,
                        Map.of(
                                "temperature", agent.temperature(),
                                "maxOutputTokens", agent.maxOutputTokens(),
                                "enabledToolIds", exposedToolIds,
                                "toolDefinitions", definitions),
                        run.id(), chatStepId, chatStepId + ":inference:" + round + logicalSuffix,
                        command.tenantId(), modelSelection.modelPoolId(),
                        modelSelection.candidates(), modelSelection.routingStrategy(),
                        modelSelection.snapshotHash(), modelSelection.fallbackEnabled());
        if (observer == null) {
            var result=inferenceApi.execute(inferenceCommand);ensureNotCancelled(run.id());return result;
        }
        boolean deferRepositoryContent = !exposedToolIds.isEmpty();
        long[] checkedAt = {0};
        var response = inferenceApi.executeStreaming(
                inferenceCommand,
                new InferenceExecutionApi.InferenceStreamObserver() {
                    @Override
                    public void onReasoningDelta(String content) {
                        if (System.nanoTime()-checkedAt[0]>250_000_000L) { ensureNotCancelled(run.id()); checkedAt[0]=System.nanoTime(); }
                        observer.onReasoningDelta(content);
                    }

                    @Override
                    public void onContentDelta(String content) {
                        if (System.nanoTime()-checkedAt[0]>250_000_000L) { ensureNotCancelled(run.id()); checkedAt[0]=System.nanoTime(); }
                        if (!deferRepositoryContent) observer.onContentDelta(content);
                    }
                });
        // A Tool-calling round is progress, not the final answer. Do not concatenate its preamble.
        ensureNotCancelled(run.id());
        if (deferRepositoryContent && response.toolCalls().isEmpty() && !response.content().isBlank()) {
            observer.onContentDelta(response.content());
        }
        return response;
    }

    private InferenceExecutionApi.InferenceExecutionResult completeRepositoryRead(
            ChatExecutionCommand command, AgentExecutionConfigurationView agent, ModelSelection selection,
            AgentRunView run, String stepId, List<InferenceExecutionApi.InferenceMessage> messages,
            List<String> results, List<ChatRuntimeEvent> events, ChatStreamObserver observer,
            RepositoryReadContext history) {
        int input = 0, output = 0;
        String reasoning = "";
        int limit = Math.max(1, Math.min(8, agent.maxTurns()));
        boolean noProgress = false;
        for (int round = 1; round <= limit; round++) {
            boolean finish = round == limit || noProgress;
            var followUp = history.messages(messages, finish);
            var next = executeInferenceRound(command, agent, selection, run, stepId, followUp,
                    finish ? List.of() : List.of("file_list", "file_read"), round, observer);
            input += next.inputTokens(); output += next.outputTokens();
            reasoning = joinReasoning(reasoning, next.reasoningContent());
            addEvent(events, observer, event("inference_completed", Map.of("round", round,
                    "inputTokens", next.inputTokens(), "outputTokens", next.outputTokens())));
            if (next.toolCalls().isEmpty()) {
                if (outputLimited(next)) {
                    next = continueRepositoryAnswer(command, agent, selection, run, stepId, followUp, next, 9, observer);
                    input += next.inputTokens(); output += next.outputTokens();
                    reasoning = joinReasoning(reasoning, next.reasoningContent());
                }
                return new InferenceExecutionApi.InferenceExecutionResult(next.content(), input, output,
                        List.of(), next.finishReason(), next.providerRequestId(), next.usage(), next.selectedProviderId(),
                        next.selectedModelId(), next.attemptCount(), next.candidateSnapshotHash(), reasoning);
            }
            if (finish || next.toolCalls().size() > 8 || next.toolCalls().stream()
                    .anyMatch(call -> !ProjectChatWorkspaceBinding.READ_TOOLS.contains(call.name()))) {
                throw new BusinessException("Repository read Tool budget or read-only policy exceeded",
                        HttpStatus.CONFLICT, "PROJECT_CHAT_READ_LIMIT");
            }
            final int callRound = round;
            history.assistantTurn(next.content());
            int previousEvidence = history.size();
            var calls = next.toolCalls().stream().map(call -> new InferenceExecutionApi.InferenceToolCall(
                    "repository-" + callRound + ":" + call.id(), call.name(), call.arguments())).toList();
            executeTools(run, calls, 0, null, null, results, events, observer,
                    (a,b,c,d,e) -> { throw new BusinessException("Repository reads cannot request a write approval",
                            HttpStatus.CONFLICT, "PROJECT_CHAT_READ_ONLY"); }, history);
            noProgress = history.size() == previousEvidence;
        }
        throw new IllegalStateException("Repository read budget exhausted");
    }

    private static boolean outputLimited(InferenceExecutionApi.InferenceExecutionResult result) {
        return RepositoryAnswerContinuation.limited(result.finishReason());
    }

    private int contextWindow(AgentExecutionConfigurationView agent,ModelSelection selection){
        int window=agent.maxContextTokens();
        if(modelCatalog!=null)for(var candidate:selection.candidates()){
            if("generic".equals(candidate.providerId()))continue;
            for(var model:modelCatalog.listModels(candidate.providerId())){
                if(candidate.modelId().equals(model.modelId())&&model.maxContextTokens()>0)window=Math.min(window,model.maxContextTokens());
            }
        }
        return window;
    }

    private InferenceExecutionApi.InferenceExecutionResult continuePersistedAnswer(ChatExecutionCommand command,
            AgentExecutionConfigurationView agent,ModelSelection selection,AgentRunView run,String stepId,
            List<InferenceExecutionApi.InferenceMessage> messages,InferenceExecutionApi.InferenceExecutionResult first,
            String reservation) {
        if(!outputLimited(first))return first;
        var progress=new ChatReplyProgress(conversationApi,command.tenantId(),command.userId(),command.conversationId(),reservation,null);
        progress.onContentDelta(first.content());progress.flush();
        try{
            var next=continueRepositoryAnswer(command,agent,selection,run,stepId,messages,first,9,progress);
            return new InferenceExecutionApi.InferenceExecutionResult(next.content(),first.inputTokens()+next.inputTokens(),
                    first.outputTokens()+next.outputTokens(),List.of(),next.finishReason(),next.providerRequestId(),next.usage(),
                    next.selectedProviderId(),next.selectedModelId(),next.attemptCount(),next.candidateSnapshotHash(),
                    joinReasoning(first.reasoningContent(),next.reasoningContent()));
        }finally{progress.flush();}
    }

    /** Two ledgered, tool-free continuations at most; token accounting excludes the supplied first round. */
    private InferenceExecutionApi.InferenceExecutionResult continueRepositoryAnswer(
            ChatExecutionCommand command, AgentExecutionConfigurationView agent, ModelSelection selection,
            AgentRunView run, String stepId, List<InferenceExecutionApi.InferenceMessage> messages,
            InferenceExecutionApi.InferenceExecutionResult first, int firstRound, ChatStreamObserver observer) {
        String content = first.content(), reasoning = "";
        int input = 0, output = 0;
        var last = first;
        for (int attempt = 0; attempt < 2 && outputLimited(last); attempt++) {
            var followUp = new ArrayList<>(messages);
            followUp.add(new InferenceExecutionApi.InferenceMessage("assistant", content));
            followUp.add(new InferenceExecutionApi.InferenceMessage("user",
                    "Your answer stopped at the output token limit and is incomplete. Continue exactly after its last character. "
                    + "Do not repeat the introduction, previously written sections or code fences. Finish any open code block, "
                    + "then complete the analysis concisely using the existing evidence. Do not call tools."));
            // Buffer continuation text so a repeated boundary is removed before publishing it.
            ChatStreamObserver buffered = observer == null ? null : new ChatStreamObserver() {
                public void onRuntimeEvent(ChatRuntimeEvent event) { observer.onRuntimeEvent(event); }
                public void onReasoningDelta(String text) { observer.onReasoningDelta(text); }
                public void onContentDelta(String text) { /* Published once after boundary reconciliation. */ }
            };
            last = executeInferenceRound(command, agent, selection, run, stepId, followUp, List.of(), firstRound + attempt, buffered);
            input += last.inputTokens(); output += last.outputTokens();
            if (!last.toolCalls().isEmpty()) throw new BusinessException("Answer continuation cannot call Tools",
                    HttpStatus.CONFLICT, "PROJECT_CHAT_READ_LIMIT");
            String suffix = RepositoryAnswerContinuation.suffix(content, last.content());
            content += suffix;
            reasoning = joinReasoning(reasoning, last.reasoningContent());
            if (observer != null && !suffix.isEmpty()) observer.onContentDelta(suffix);
        }
        if (outputLimited(last)) {
            String notice = command.message().codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)
                    ? "\n\n---\n⚠ 输出达到本次续写上限，回答尚未完成。请发送“继续”。"
                    : "\n\n---\nOutput limit reached: this answer is incomplete. Ask to continue.";
            content += notice;
            if (observer != null) observer.onContentDelta(notice);
        }
        return new InferenceExecutionApi.InferenceExecutionResult(content, input, output, List.of(), last.finishReason(),
                last.providerRequestId(), last.usage(), last.selectedProviderId(), last.selectedModelId(), last.attemptCount(),
                last.candidateSnapshotHash(), reasoning);
    }

    private List<String> modelVisibleToolIds(
            AgentExecutionConfigurationView agent,
            AgentRunView run,
            List<String> configuredToolIds) {
        boolean readAttachment = projectChatWorkspace != null && projectChatWorkspace.find(run).isPresent();
        var capabilities = capabilityCatalogApi.resolveTools(configuredToolIds).stream()
                .filter(tool -> !readAttachment || !tool.requiresWorkspace()
                        || ProjectChatWorkspaceBinding.READ_TOOLS.contains(tool.id())).toList();
        return RuntimeToolExposurePolicy.eligibleToolIds(
                capabilities,
                agent.ragEnabled(), !agent.knowledgeBaseIds().isEmpty() || !agent.knowledgeCollectionIds().isEmpty(),
                agent.networkEnabled(), readAttachment || (run.workspaceId() != null
                        && run.projectId() != null && run.taskId() != null));
    }

    private ToolProgress executeTools(
            AgentRunView run,
            List<InferenceExecutionApi.InferenceToolCall> calls,
            int startIndex,
            String resumedToolStepId,
            String approvalId,
            List<String> results,
            List<ChatRuntimeEvent> events,
            ChatStreamObserver observer,
            ApprovalCheckpointFactory checkpointFactory) {
        return executeTools(run, calls, startIndex, resumedToolStepId, approvalId, results, events, observer, checkpointFactory, null);
    }

    private ToolProgress executeTools(AgentRunView run, List<InferenceExecutionApi.InferenceToolCall> calls,
            int startIndex, String resumedToolStepId, String approvalId, List<String> results,
            List<ChatRuntimeEvent> events, ChatStreamObserver observer,
            ApprovalCheckpointFactory checkpointFactory, RepositoryReadContext history) {
        for (int index = startIndex; index < calls.size(); index++) {
            ensureNotCancelled(run.id());
            InferenceExecutionApi.InferenceToolCall call = calls.get(index);
            if (history != null && ProjectChatWorkspaceBinding.READ_TOOLS.contains(call.name()) && history.contains(call)) continue;
            runtimeApi.markRunWaitingForTool(run.id());
            String toolStepId = index == startIndex && resumedToolStepId != null
                    ? resumedToolStepId
                    : runtimeApi.startStep(new StartRunStepCommand(
                            run.id(), "tool:" + call.name())).id();
            addEvent(events, observer, event("tool_call", Map.of(
                    "toolCallId", call.id(), "toolName", call.name(), "arguments", call.arguments())));
            checkpoint(run.id(), toolStepId, "before-tool", Map.of(
                    "toolCallId", call.id(), "toolName", call.name()));
            RuntimeToolExecutionApplicationApi.RuntimeToolResult result;
            try {
                result = toolingApi.execute(
                        new RuntimeToolExecutionApplicationApi.ExecuteRuntimeToolCommand(
                                run.ownerId(), run.id(), toolStepId, call.id(),
                                call.name(), withApproval(call.arguments(),
                                        index == startIndex ? approvalId : null)));
            } catch (GovernanceApprovalRequiredException required) {
                ChatToolWaitCheckpoint pending = checkpointFactory.create(
                        index, toolStepId, ChatToolWaitCheckpoint.APPROVAL,
                        required.approvalId(), null);
                saveToolWaitCheckpoint(run.id(), toolStepId, pending);
                runtimeApi.markRunWaitingForUser(run.id());
                addEvent(events, observer, event("approval_required", Map.of(
                        "agentRunId", run.id(),
                        "approvalId", required.approvalId(),
                        "toolCallId", call.id(),
                        "toolName", call.name())));
                return new ToolProgress(
                        "WAITING_APPROVAL", required.approvalId(),
                        call.id(), call.name(), null);
            }
            if ("UNKNOWN".equals(result.status())) {
                if (result.ledgerRevision() == null) {
                    throw new BusinessException(
                            "UNKNOWN Tool result has no ledger revision",
                            HttpStatus.CONFLICT, "CHAT_TOOL_UNKNOWN_EVIDENCE_INVALID");
                }
                ChatToolWaitCheckpoint pending = checkpointFactory.create(
                        index, toolStepId, ChatToolWaitCheckpoint.UNKNOWN,
                        null, result.ledgerRevision());
                saveToolWaitCheckpoint(run.id(), toolStepId, pending);
                runtimeApi.markRunWaitingForUser(run.id());
                addEvent(events, observer, event("tool_reconciliation_required", Map.of(
                        "agentRunId", run.id(),
                        "toolCallId", call.id(),
                        "toolName", call.name(),
                        "ledgerRevision", result.ledgerRevision())));
                return new ToolProgress(
                        "WAITING_RECONCILIATION", null,
                        call.id(), call.name(), result.ledgerRevision());
            }
            if ("SUCCEEDED".equals(result.status())) {
                runtimeApi.completeStep(new CompleteRunStepCommand(run.id(), toolStepId));
            } else {
                runtimeApi.failStep(new FailRunStepCommand(run.id(), toolStepId));
            }
            checkpoint(run.id(), toolStepId, "after-tool", Map.of(
                    "toolCallId", call.id(), "status", result.status()));
            addEvent(events, observer, event("tool_result", Map.of(
                    "toolCallId", call.id(),
                    "status", result.status(),
                    "result", result.result() == null ? "" : result.result())));
            if (result.result() != null && !result.result().isBlank()) {
                results.add(result.result().trim());
            }
            if (history != null) history.record(call, result.status(), result.result());
        }
        return ToolProgress.completed();
    }

    private ChatExecutionView resumeFromCheckpoint(
            ChatToolWaitCheckpoint checkpoint,
            String approvalId) {
        AgentRunView run = runtimeApi.findRun(checkpoint.agentRunId())
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found", HttpStatus.NOT_FOUND, "CHAT_RUN_NOT_FOUND"));
        AgentExecutionConfigurationView agent = configuration(run);
        if (!checkpoint.agentId().equals(agent.agentId())) {
            throw new BusinessException(
                    "Run configuration does not match the suspended Chat",
                    HttpStatus.CONFLICT, "CHAT_APPROVAL_CONFIGURATION_MISMATCH");
        }
        ConversationView conversation = conversationApi.find(checkpoint.conversationId())
                .filter(value -> checkpoint.tenantId().equals(value.tenantId()))
                .filter(value -> checkpoint.ownerId().equals(value.userId()))
                .orElseThrow(() -> new BusinessException(
                        "Conversation not found", HttpStatus.NOT_FOUND));
        ModelSelection modelSelection = fromSnapshot(checkpoint.modelSelection());
        List<InferenceExecutionApi.InferenceMessage> messages = checkpoint.messages().stream()
                .map(value -> new InferenceExecutionApi.InferenceMessage(
                        value.role(), value.content()))
                .toList();
        List<InferenceExecutionApi.InferenceToolCall> calls = checkpoint.toolCalls().stream()
                .map(value -> new InferenceExecutionApi.InferenceToolCall(
                        value.id(), value.name(), value.arguments()))
                .toList();
        InferenceExecutionApi.InferenceExecutionResult initial =
                new InferenceExecutionApi.InferenceExecutionResult(
                        checkpoint.initialContent(), checkpoint.initialInputTokens(),
                        checkpoint.initialOutputTokens(), calls, "tool_calls", null, Map.of(),
                        modelSelection.providerId(), modelSelection.modelId(), 1,
                        modelSelection.snapshotHash(), checkpoint.initialReasoningContent());
        ChatExecutionCommand command = new ChatExecutionCommand(
                checkpoint.tenantId(), checkpoint.ownerId(), checkpoint.conversationId(),
                checkpoint.agentId(), checkpoint.userMessage(), null, List.of());
        List<String> results = new ArrayList<>(checkpoint.toolResults());
        List<ChatRuntimeEvent> events = new ArrayList<>();
        Map<String, Object> resumeEvidence = new LinkedHashMap<>();
        resumeEvidence.put("agentRunId", run.id());
        resumeEvidence.put("resumeKind", checkpoint.waitKind());
        if (approvalId != null) resumeEvidence.put("approvalId", approvalId);
        addEvent(events, null, event("runtime_resumed", resumeEvidence));

        ToolProgress progress = executeTools(
                run, calls, checkpoint.nextToolIndex(), checkpoint.pendingToolStepId(),
                approvalId, results, events, null,
                (toolIndex, toolStepId, waitKind, nextApprovalId, toolRevision) ->
                        copyApprovalCheckpoint(
                                checkpoint, toolIndex, results, toolStepId,
                                nextApprovalId, toolRevision, waitKind));
        if (progress.waiting()) {
            ChatExecutionView waiting = waitingView(
                    run.id(), conversation.id(), initial, checkpoint.recalledMemories(),
                    checkpoint.citations(), progress, events, checkpoint.chatTaskId());
            if (checkpoint.planProgress() == null) return waiting;
            TaskPlanView plan = taskPlanApi.getChatPlan(
                    new com.spaceagent.platform.project.api.GetChatTaskPlanQuery(
                            checkpoint.tenantId(), checkpoint.ownerId(),
                            checkpoint.conversationId(), checkpoint.chatTaskId(),
                            checkpoint.planProgress().taskPlanId()));
            return withPlan(waiting, plan);
        }

        InferenceExecutionApi.InferenceExecutionResult finalInference = initial;
        if (!results.isEmpty()) {
            List<InferenceExecutionApi.InferenceMessage> followUp = new ArrayList<>(messages);
            followUp.add(new InferenceExecutionApi.InferenceMessage(
                    "user", toolEvidence(checkpoint.userMessage(), results)));
            addEvent(events, null, event("tool_synthesis_started", Map.of(
                    "toolResultCount", results.size())));
            finalInference = executeInferenceRound(
                    command, agent, modelSelection, run, checkpoint.chatStepId(), followUp,
                    List.of(), 1, null);
            if(checkpoint.planProgress()==null) finalInference=continuePersistedAnswer(command,agent,modelSelection,run,
                    checkpoint.chatStepId(),followUp,finalInference,checkpoint.assistantReservationId());
            addEvent(events, null, event("inference_completed", Map.of(
                    "round", 1,
                    "inputTokens", finalInference.inputTokens(),
                    "outputTokens", finalInference.outputTokens())));
            checkpoint(run.id(), checkpoint.chatStepId(), "tool-synthesis-completed", Map.of(
                    "toolResultCount", results.size()));
        }
        String assistantMessage = finalAnswer(finalInference.content());
        String reasoningContent = joinReasoning(
                initial.reasoningContent(), finalInference == initial
                        ? "" : finalInference.reasoningContent());
        if (checkpoint.planProgress() != null) {
            TaskPlanView plan = taskPlanApi.getChatPlan(
                    new com.spaceagent.platform.project.api.GetChatTaskPlanQuery(
                            checkpoint.tenantId(), checkpoint.ownerId(),
                            checkpoint.conversationId(), checkpoint.chatTaskId(),
                            checkpoint.planProgress().taskPlanId()));
            var currentStep = plan.steps().stream()
                    .filter(step -> step.id().equals(checkpoint.planProgress().planStepId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(
                            "Planned Chat checkpoint Step is missing", HttpStatus.CONFLICT,
                            "CHAT_PLAN_STEP_CHECKPOINT_MISMATCH"));
            runtimeApi.completeStep(new CompleteRunStepCommand(
                    run.id(), checkpoint.chatStepId()));
            taskPlanApi.transitionChatPlanStep(new TransitionChatPlanStepExecutionCommand(
                    checkpoint.tenantId(), checkpoint.ownerId(), checkpoint.conversationId(),
                    checkpoint.chatTaskId(), currentStep.childTaskId(), plan.id(), currentStep.id(),
                    PlanStepExecutionAction.COMPLETE));
            List<ChatToolWaitCheckpoint.StepResult> completed =
                    new ArrayList<>(checkpoint.planProgress().completedSteps());
            completed.add(new ChatToolWaitCheckpoint.StepResult(
                    currentStep.id(), currentStep.stepKey(),
                    abbreviate(finalAnswer(finalInference.content()), 8_000)));
            checkpoint(run.id(), checkpoint.chatStepId(), "chat-plan-step-completed", Map.of(
                    "taskPlanId", plan.id(),
                    "completedPlanStepId", currentStep.id(),
                    "completedSteps", List.copyOf(completed),
                    "modelSelection", checkpoint.modelSelection()));
            ChatPlanReviewCheckpoint review = new ChatPlanReviewCheckpoint(
                    ChatPlanReviewCheckpoint.SCHEMA, checkpoint.tenantId(), checkpoint.ownerId(),
                    run.id(), checkpoint.conversationId(), checkpoint.agentId(),
                    checkpoint.runConfigurationSnapshotId(), checkpoint.chatTaskId(), plan.id(),
                    checkpoint.assistantReservationId(), checkpoint.assistantSequence(),
                    checkpoint.userMessage());
            return executeActivePlan(
                    review, plan, completed, fromSnapshot(checkpoint.modelSelection()));
        }
        if(outputLimited(finalInference))conversationApi.updateReplyDraft(checkpoint.tenantId(),checkpoint.ownerId(),conversation.id(),checkpoint.assistantReservationId(),assistantMessage);
        else conversationApi.completeReply(
                checkpoint.tenantId(), checkpoint.ownerId(), conversation.id(),
                checkpoint.assistantReservationId(), assistantMessage);
        ConversationContextSnapshotView contextSnapshot = snapshotApi.save(
                new SaveConversationContextSnapshotCommand(
                        conversation.id(),
                        Math.max(1, (checkpoint.assistantSequence() + 1) / 2),
                        abbreviate(assistantMessage, 2000), 0,
                        checkpoint.assistantSequence(),
                        Math.max(1, assistantMessage.length() / 4),
                        "sha256:" + sha256(conversation.id() + ":"
                                + checkpoint.assistantSequence() + ":" + assistantMessage)));
        MemoryEvaluationView memory = agent.memoryEnabled() ? memoryApi.evaluateMessage(new EvaluateMessageMemoryCommand(
                checkpoint.ownerId(), conversation.id(), checkpoint.userMessage(), assistantMessage)) : MemoryEvaluationView.skipped();
        runtimeApi.completeStep(new CompleteRunStepCommand(run.id(), checkpoint.chatStepId()));
        transitionChatTask(
                checkpoint.tenantId(), checkpoint.ownerId(), checkpoint.conversationId(),
                checkpoint.chatTaskId(), TaskTransition.COMPLETE);
        Map<String, Object> completionEvidence = new LinkedHashMap<>();
        completionEvidence.put("conversationSnapshotId", contextSnapshot.id());
        completionEvidence.put("conversationSnapshotVersion", contextSnapshot.version());
        completionEvidence.put("resumeKind", checkpoint.waitKind());
        if (approvalId != null) completionEvidence.put("resumedApprovalId", approvalId);
        checkpoint(run.id(), checkpoint.chatStepId(), "completed", completionEvidence);
        addEvent(events, null, event("runtime_completed", Map.of("agentRunId", run.id())));
        return new ChatExecutionView(
                conversation.id(), run.id(), assistantMessage,
                memory.candidateCreated(),
                memory.candidate() == null ? null : memory.candidate().id(),
                checkpoint.recalledMemories().size(), checkpoint.recalledMemories(),
                !checkpoint.citations().isEmpty(), checkpoint.citations().size(),
                checkpoint.citations(),
                initial.inputTokens() + (finalInference == initial ? 0 : finalInference.inputTokens()),
                initial.outputTokens() + (finalInference == initial ? 0 : finalInference.outputTokens()),
                events, reasoningContent, "COMPLETED", null, null, null, null,
                checkpoint.chatTaskId(), checkpoint.chatTaskId() == null ? null : "COMPLETED");
    }

    private ChatToolWaitCheckpoint approvalCheckpoint(String runId) {
        return toolWaitCheckpoint(
                runId, "chat-approval-waiting", "CHAT_APPROVAL_CHECKPOINT");
    }

    private ChatPlanReviewCheckpoint planReviewCheckpoint(String runId) {
        var persisted = runtimeApi.findLatestCheckpointByPhase(runId, "chat-plan-review")
                .orElseThrow(() -> new BusinessException(
                        "Chat plan review checkpoint is missing", HttpStatus.CONFLICT,
                        "CHAT_PLAN_REVIEW_CHECKPOINT_MISSING"));
        try {
            JsonNode root = objectMapper.readTree(persisted.stateSnapshot());
            ChatPlanReviewCheckpoint value = objectMapper.treeToValue(
                    root.path("planReview"), ChatPlanReviewCheckpoint.class);
            if (!runId.equals(value.agentRunId())) throw new IllegalArgumentException("Run mismatch");
            return value;
        } catch (Exception error) {
            throw new BusinessException(
                    "Chat plan review checkpoint is invalid", HttpStatus.CONFLICT,
                    "CHAT_PLAN_REVIEW_CHECKPOINT_INVALID");
        }
    }

    private void requireLatestCheckpointPhase(String runId, String phase, String code) {
        var latest = runtimeApi.findLatestCheckpoint(runId)
                .orElseThrow(() -> new BusinessException(
                        "Chat checkpoint is missing", HttpStatus.CONFLICT, code));
        try {
            if (!phase.equals(objectMapper.readTree(latest.stateSnapshot()).path("phase").asText())) {
                throw new BusinessException(
                        "Chat Run is waiting at another recovery boundary",
                        HttpStatus.CONFLICT, code);
            }
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(
                    "Chat checkpoint is invalid", HttpStatus.CONFLICT, code);
        }
    }

    private ChatToolWaitCheckpoint unknownCheckpoint(String runId) {
        return toolWaitCheckpoint(
                runId, "chat-tool-unknown", "CHAT_TOOL_UNKNOWN_CHECKPOINT");
    }

    private ChatToolWaitCheckpoint toolWaitCheckpoint(
            String runId, String phase, String errorPrefix) {
        var persisted = runtimeApi.findLatestCheckpointByPhase(
                        runId, phase)
                .orElseThrow(() -> new BusinessException(
                        "Chat Tool-wait checkpoint is missing", HttpStatus.CONFLICT,
                        errorPrefix + "_MISSING"));
        try {
            JsonNode root = objectMapper.readTree(persisted.stateSnapshot());
            JsonNode toolWait = root.has("toolWait")
                    ? root.path("toolWait") : root.path("approval");
            if (!phase.equals(root.path("phase").asText()) || !toolWait.isObject()) {
                throw new IllegalArgumentException("Latest checkpoint is not the expected Tool wait");
            }
            ChatToolWaitCheckpoint value = objectMapper.treeToValue(
                    toolWait, ChatToolWaitCheckpoint.class);
            if (!runId.equals(value.agentRunId())) {
                throw new IllegalArgumentException("Checkpoint Run mismatch");
            }
            return value;
        } catch (Exception error) {
            throw new BusinessException(
                    "Chat Tool-wait checkpoint is invalid", HttpStatus.CONFLICT,
                    errorPrefix + "_INVALID");
        }
    }

    private ChatToolWaitCheckpoint approvalCheckpoint(
            ChatExecutionCommand command,
            AgentExecutionConfigurationView agent,
            ModelSelection modelSelection,
            ConversationView conversation,
            RunStepView chatStep,
            String assistantReservationId,
            int assistantSequence,
            String chatTaskId,
            List<InferenceExecutionApi.InferenceMessage> messages,
            InferenceExecutionApi.InferenceExecutionResult inference,
            int toolIndex,
            List<String> toolResults,
            List<String> recalledMemories,
            List<String> citations,
            String toolStepId,
            String approvalId,
            Long pendingToolRevision,
            String waitKind) {
        return new ChatToolWaitCheckpoint(
                ChatToolWaitCheckpoint.APPROVAL.equals(waitKind)
                        ? ChatToolWaitCheckpoint.APPROVAL_SCHEMA
                        : ChatToolWaitCheckpoint.UNKNOWN_SCHEMA,
                command.tenantId(), command.userId(),
                chatStep.agentRunId(),
                conversation.id(), agent.agentId(), chatStep.agentRunId(), chatStep.id(),
                assistantReservationId, assistantSequence, chatTaskId, command.message(),
                toSnapshot(modelSelection),
                messages.stream().map(value -> new ChatToolWaitCheckpoint.MessageSnapshot(
                        value.role(), value.content())).toList(),
                inference.toolCalls().stream().map(value ->
                        new ChatToolWaitCheckpoint.ToolCallSnapshot(
                                value.id(), value.name(), value.arguments())).toList(),
                toolIndex, List.copyOf(toolResults), inference.content(),
                inference.inputTokens(), inference.outputTokens(), inference.reasoningContent(),
                recalledMemories, citations, toolStepId, approvalId,
                pendingToolRevision, waitKind);
    }

    private ChatToolWaitCheckpoint copyApprovalCheckpoint(
            ChatToolWaitCheckpoint source,
            int toolIndex,
            List<String> toolResults,
            String toolStepId,
            String approvalId,
            Long pendingToolRevision,
            String waitKind) {
        return new ChatToolWaitCheckpoint(
                ChatToolWaitCheckpoint.APPROVAL.equals(waitKind)
                        ? ChatToolWaitCheckpoint.APPROVAL_SCHEMA
                        : ChatToolWaitCheckpoint.UNKNOWN_SCHEMA,
                source.tenantId(), source.ownerId(), source.agentRunId(),
                source.conversationId(),
                source.agentId(), source.runConfigurationSnapshotId(), source.chatStepId(),
                source.assistantReservationId(), source.assistantSequence(), source.chatTaskId(),
                source.userMessage(),
                source.modelSelection(), source.messages(), source.toolCalls(), toolIndex,
                List.copyOf(toolResults), source.initialContent(), source.initialInputTokens(),
                source.initialOutputTokens(), source.initialReasoningContent(),
                source.recalledMemories(), source.citations(), toolStepId, approvalId,
                pendingToolRevision, waitKind, source.planProgress());
    }

    private void saveToolWaitCheckpoint(
            String runId, String toolStepId, ChatToolWaitCheckpoint wait) {
        JsonNode value = objectMapper.valueToTree(wait);
        String serialized = value.toString();
        if (serialized.length() > MAX_APPROVAL_CHECKPOINT_CHARACTERS) {
            throw new BusinessException(
                    "Chat approval checkpoint exceeds the recovery limit",
                    HttpStatus.PAYLOAD_TOO_LARGE, "CHAT_APPROVAL_CHECKPOINT_TOO_LARGE");
        }
        String phase = ChatToolWaitCheckpoint.APPROVAL.equals(wait.waitKind())
                ? "chat-approval-waiting" : "chat-tool-unknown";
        checkpoint(runId, toolStepId, phase, Map.of("toolWait", value));
    }

    private ChatExecutionView waitingView(
            String runId,
            String conversationId,
            InferenceExecutionApi.InferenceExecutionResult inference,
            List<String> recalledMemories,
            List<String> citations,
            ToolProgress progress,
            List<ChatRuntimeEvent> events,
            String chatTaskId) {
        return new ChatExecutionView(
                conversationId, runId, "", false, null,
                recalledMemories.size(), recalledMemories,
                !citations.isEmpty(), citations.size(), citations,
                inference.inputTokens(), inference.outputTokens(), events,
                inference.reasoningContent(), progress.executionState(),
                progress.approvalId(), progress.toolCallId(), progress.toolName(),
                progress.toolRevision(), chatTaskId,
                chatTaskId == null ? null : "IN_PROGRESS");
    }

    private String withApproval(String arguments, String approvalId) {
        if (approvalId == null || approvalId.isBlank()) return arguments;
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            if (!parsed.isObject()) throw new IllegalArgumentException("Tool arguments must be an object");
            var copy = parsed.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("approvalId", approvalId);
            return objectMapper.writeValueAsString(copy);
        } catch (Exception error) {
            throw new BusinessException(
                    "Suspended Tool arguments are invalid", HttpStatus.CONFLICT,
                    "CHAT_APPROVAL_TOOL_ARGUMENTS_INVALID");
        }
    }

    private ChatToolWaitCheckpoint.ModelSelectionSnapshot toSnapshot(ModelSelection selection) {
        return new ChatToolWaitCheckpoint.ModelSelectionSnapshot(
                selection.modelPoolId(), selection.candidates().stream().map(value ->
                        new ChatToolWaitCheckpoint.ModelCandidateSnapshot(
                                value.memberId(), value.providerId(), value.providerModelId(),
                                value.modelId(), value.priority(), value.weight(),
                                value.healthLatencyMs(), value.priceId(),
                                value.inputMicrosPerMillionTokens(),
                                value.outputMicrosPerMillionTokens())).toList(),
                selection.routingStrategy(), selection.fallbackEnabled(), selection.snapshotHash());
    }

    private ModelSelection fromSnapshot(ChatToolWaitCheckpoint.ModelSelectionSnapshot value) {
        return new ModelSelection(
                value.modelPoolId(), value.candidates().stream().map(candidate ->
                        new InferenceExecutionApi.InferenceCandidate(
                                candidate.memberId(), candidate.providerId(),
                                candidate.providerModelId(), candidate.modelId(),
                                candidate.priority(), candidate.weight(),
                                candidate.healthLatencyMs(), candidate.priceId(),
                                candidate.inputMicrosPerMillionTokens(),
                                candidate.outputMicrosPerMillionTokens())).toList(),
                value.routingStrategy(), value.fallbackEnabled(), value.snapshotHash());
    }

    @FunctionalInterface
    private interface ApprovalCheckpointFactory {
        ChatToolWaitCheckpoint create(
                int toolIndex, String toolStepId, String waitKind,
                String approvalId, Long toolRevision);
    }

    private record ToolProgress(
            String executionState,
            String approvalId,
            String toolCallId,
            String toolName,
            Long toolRevision) {
        private boolean waiting() {
            return executionState != null;
        }

        private static ToolProgress completed() {
            return new ToolProgress(null, null, null, null, null);
        }
    }

    private List<Map<String, Object>> toolDefinitions(List<String> toolIds) {
        return capabilityCatalogApi.resolveTools(toolIds).stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.id(),
                        "description", tool.description(),
                        "parameters", tool.inputSchema()))
                .toList();
    }







    private void checkpoint(String runId, String stepId, String phase, Map<String, Object> values) {
        Map<String, Object> snapshot = new LinkedHashMap<>(values);
        snapshot.put("phase", phase);
        snapshot.put("stepId", stepId);
        try {
            runtimeApi.createCheckpoint(new CreateCheckpointCommand(
                    runId, objectMapper.writeValueAsString(snapshot)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize chat checkpoint", exception);
        }
    }

    private void failRun(
            String runId, String stepId, RuntimeException exception, boolean ownRunLifecycle) {
        try {
            runtimeApi.failStep(new FailRunStepCommand(runId, stepId));
        } catch (RuntimeException ignored) {
            // Preserve the original failure.
        }
        if (ownRunLifecycle) {
            try {
                runtimeApi.fail(new FailAgentRunCommand(
                        runId, abbreviate(exception.getMessage(), 500)));
            } catch (RuntimeException ignored) {
                // Preserve the original failure.
            }
        }
    }

    private void ensureNotCancelled(String runId) {
        if (runtimeApi.findRun(runId).map(run->run.state()==AgentRunState.CANCELLED).orElse(false))
            throw new java.util.concurrent.CancellationException("Chat Run cancelled");
    }

    private ChatRuntimeEvent event(String type, Map<String, Object> data) {
        return new ChatRuntimeEvent(type, data);
    }

    private void addEvent(
            List<ChatRuntimeEvent> events,
            ChatStreamObserver observer,
            ChatRuntimeEvent event) {
        events.add(event);
        if (observer != null) observer.onRuntimeEvent(event);
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash chat snapshot", exception);
        }
    }

    private KnowledgeRetrievalView retrieveKnowledge(String tenant,String actorId,String runId, AgentExecutionConfigurationView agent, String query) {
        if (!agent.ragEnabled()) return new KnowledgeRetrievalView(query, List.of());
        if (!agent.knowledgeCollectionIds().isEmpty()) {
            if(collectionRetrieval==null)throw new BusinessException("Knowledge retrieval unavailable",HttpStatus.SERVICE_UNAVAILABLE,"KNOWLEDGE_VECTOR_UNAVAILABLE");
            return collectionRetrieval.retrieve(new com.spaceagent.platform.knowledge.api.KnowledgeCollectionRetrievalApi.Query(
                    new com.spaceagent.platform.knowledge.api.KnowledgeBaseApplicationApi.Actor(actorId,tenant),agent.knowledgeCollectionIds(),query,5,
                    Math.max(64,Math.min(2048,agent.maxContextTokens()/4)),runId)).legacyView(query);
        }
        return agent.knowledgeBaseIds().isEmpty() ? new KnowledgeRetrievalView(query, List.of())
                : knowledgeApi.retrieve(new KnowledgeRetrievalCommand(actorId, agent.knowledgeBaseIds(), query, 5));
    }

    private AgentExecutionConfigurationView configuration(AgentRunView run) {
        AgentRunConfigurationSnapshot snapshot = runConfigurations.findByRunId(
                        run.tenantId(), run.ownerId(), run.id())
                .filter(value -> value.state() == AgentRunConfigurationSnapshot.State.SNAPSHOTTED)
                .orElseThrow(() -> new BusinessException(
                        "Run Agent configuration snapshot is unavailable",
                        HttpStatus.CONFLICT,
                        "RUN_AGENT_CONFIGURATION_UNAVAILABLE"));
        return new AgentExecutionConfigurationView(
                snapshot.agentId(), snapshot.configHash(),
                snapshot.modelPoolId(), snapshot.modelProviderId(), snapshot.modelId(),
                snapshot.systemPrompt(), snapshot.temperature(), snapshot.maxContextTokens(),
                snapshot.maxOutputTokens(), snapshot.maxTurns(), snapshot.memoryEnabled(),
                snapshot.ragEnabled(), snapshot.knowledgeBaseIds(), snapshot.enabledToolIds(),
                snapshot.skillIds(), snapshot.permissionMode(), snapshot.networkEnabled(),
                snapshot.agentRevision(), snapshot.mcpBindings().stream()
                        .map(binding -> new AgentExecutionConfigurationView.McpBindingView(
                                binding.sourceBindingId(), snapshot.agentId(),
                                binding.installationId(), binding.connectionId(),
                                binding.serverVersionId(), binding.capabilitySnapshotId(),
                                binding.connectionRevision(), binding.snapshotSha256(),
                                binding.allowedToolNames(), binding.bindingSha256(),
                                snapshot.sourceUpdatedBy(), snapshot.sourceUpdatedAt()))
                        .toList(), snapshot.knowledgeCollectionIds());
    }

    private record ModelSelection(
            String modelPoolId,
            List<InferenceExecutionApi.InferenceCandidate> candidates,
            String routingStrategy,
            boolean fallbackEnabled,
            String snapshotHash) {

        private String providerId(){return candidates.getFirst().providerId();}
        private String modelId(){return candidates.getFirst().modelId();}

        private Map<String, Object> evidence() {
            Map<String, Object> evidence = new LinkedHashMap<>();
            if (modelPoolId != null) {
                evidence.put("modelPoolId", modelPoolId);
            }
            if (providerId() != null) {
                evidence.put("modelProviderId", providerId());
            }
            if (modelId() != null) {
                evidence.put("modelId", modelId());
            }
            evidence.put("modelCandidateCount", candidates.size());
            evidence.put("modelRoutingStrategy",routingStrategy);
            if(snapshotHash!=null)evidence.put("modelCandidateSnapshotHash",snapshotHash);
            evidence.put("modelCandidates",candidates.stream().map(v->Map.of(
                    "memberId",v.memberId()==null?"direct":v.memberId(),
                    "providerId",v.providerId(),"modelId",v.modelId(),
                    "priority",v.priority(),"weight",v.weight())).toList());
            return evidence;
        }
    }

    private record CompiledChatContext(
            ContextPackage context, List<SkillCapabilityView> skills) {
        private CompiledChatContext {
            skills = List.copyOf(skills);
        }
    }
}
