package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.AdvanceExecutionCursorCommand;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.InvokeMultiAgentOrchestrationCommand;
import com.spaceagent.platform.runtime.api.MultiAgentOrchestrationApplicationApi;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.api.RecordRunEventCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.domain.MultiAgentOrchestrationPort;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.domain.SupervisorPolicy;
import com.spaceagent.platform.runtime.domain.SupervisorPolicyMode;
import com.spaceagent.platform.runtime.domain.SupervisorPolicyResolution;
import com.spaceagent.platform.runtime.domain.SupervisorRunPolicy;
import com.spaceagent.platform.runtime.domain.SupervisorRunPolicyRepository;
import com.spaceagent.platform.runtime.domain.SupervisorShadowComparison;
import com.spaceagent.platform.runtime.domain.SupervisorShadowTelemetry;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;
import com.spaceagent.platform.project.api.CreateChatTaskPlanProposalCommand;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Coordinates and records one bounded TypeScript command or Provider-reasoning boundary. */
@Service
public class MultiAgentOrchestrationApplicationService
        implements MultiAgentOrchestrationApplicationApi {

    private final RuntimeApplicationApi runtimeApi;
    private final MultiAgentOrchestrationPort orchestrationPort;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final MultiAgentCollaborationApplicationApi collaborationApi;
    private final ProviderBackedSupervisorReasoningService reasoningService;
    private final boolean providerReasoningEnabled;
    private final TaskPlanApplicationApi taskPlanApi;
    private SupervisorRunPolicyRepository supervisorPolicies;
    private TimeProvider supervisorTime;
    private SupervisorPolicyMode supervisorMode=SupervisorPolicyMode.DETERMINISTIC;
    private boolean supervisorKillSwitch;
    private SupervisorShadowTelemetry supervisorShadowTelemetry = SupervisorShadowTelemetry.noop();

    public MultiAgentOrchestrationApplicationService(
            RuntimeApplicationApi runtimeApi,
            MultiAgentOrchestrationPort orchestrationPort,
            IdGenerator idGenerator,
            ObjectMapper objectMapper) {
        this(runtimeApi, orchestrationPort, idGenerator, objectMapper,
                null, null, false, null);
    }

    public MultiAgentOrchestrationApplicationService(
            RuntimeApplicationApi runtimeApi,
            MultiAgentOrchestrationPort orchestrationPort,
            IdGenerator idGenerator,
            ObjectMapper objectMapper,
            MultiAgentCollaborationApplicationApi collaborationApi) {
        this(runtimeApi, orchestrationPort, idGenerator, objectMapper,
                collaborationApi, null, false, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MultiAgentOrchestrationApplicationService(
            RuntimeApplicationApi runtimeApi,
            MultiAgentOrchestrationPort orchestrationPort,
            IdGenerator idGenerator,
            ObjectMapper objectMapper,
            MultiAgentCollaborationApplicationApi collaborationApi,
            ProviderBackedSupervisorReasoningService reasoningService,
            TaskPlanApplicationApi taskPlanApi,
            @Value("${platform.multi-agent-orchestrator.reasoning-mode:deterministic}")
            String reasoningMode) {
        this(runtimeApi, orchestrationPort, idGenerator, objectMapper,
                collaborationApi, reasoningService,
                "provider".equalsIgnoreCase(reasoningMode), taskPlanApi);
    }

    public MultiAgentOrchestrationApplicationService(
            RuntimeApplicationApi runtimeApi,
            MultiAgentOrchestrationPort orchestrationPort,
            IdGenerator idGenerator,
            ObjectMapper objectMapper,
            MultiAgentCollaborationApplicationApi collaborationApi,
            ProviderBackedSupervisorReasoningService reasoningService,
            boolean providerReasoningEnabled) {
        this(runtimeApi, orchestrationPort, idGenerator, objectMapper, collaborationApi,
                reasoningService, providerReasoningEnabled, null);
    }

    public MultiAgentOrchestrationApplicationService(
            RuntimeApplicationApi runtimeApi,
            MultiAgentOrchestrationPort orchestrationPort,
            IdGenerator idGenerator,
            ObjectMapper objectMapper,
            MultiAgentCollaborationApplicationApi collaborationApi,
            ProviderBackedSupervisorReasoningService reasoningService,
            boolean providerReasoningEnabled,
            TaskPlanApplicationApi taskPlanApi) {
        this.runtimeApi = runtimeApi;
        this.orchestrationPort = orchestrationPort;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.collaborationApi = collaborationApi;
        this.reasoningService = reasoningService;
        this.providerReasoningEnabled = providerReasoningEnabled;
        this.taskPlanApi = taskPlanApi;
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureSupervisorPolicy(SupervisorRunPolicyRepository policies,TimeProvider time,
            @Value("${platform.supervisor.mode:DETERMINISTIC}") String mode,
            @Value("${platform.supervisor.kill-switch:false}") boolean killSwitch){this.supervisorPolicies=policies;this.supervisorTime=time;this.supervisorMode=SupervisorPolicyMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));this.supervisorKillSwitch=killSwitch;}

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureSupervisorShadowTelemetry(
            SupervisorShadowTelemetry supervisorShadowTelemetry) {
        this.supervisorShadowTelemetry = supervisorShadowTelemetry;
    }

    @Override
    public MultiAgentOrchestrationResponse invoke(
            InvokeMultiAgentOrchestrationCommand command) {
        AgentRunView run = runtimeApi.findRun(command.agentRunId())
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found: " + command.agentRunId(),
                        HttpStatus.NOT_FOUND));
        if (run.tenantId() == null || run.tenantId().isBlank()) {
            throw new BusinessException(
                    "Multi-Agent orchestration requires an Organization-scoped AgentRun",
                    HttpStatus.CONFLICT,
                    "MULTI_AGENT_RUN_NOT_ORGANIZATION_SCOPED");
        }

        String requestId = idGenerator.nextId();
        List<MultiAgentOrchestrationRequest.AgentRef> agentRefs =
                command.agentRefs().isEmpty()
                        ? List.of(new MultiAgentOrchestrationRequest.AgentRef(
                                run.agentId(), run.id(), "supervisor"))
                        : command.agentRefs();
        SupervisorPolicy supervisorPolicy=resolveSupervisorPolicy(run);
        boolean useProviderReasoning = (supervisorPolicies==null?providerReasoningEnabled:supervisorPolicy.executesProvider())
                && command.modelPoolRef() != null
                && !command.modelPoolRef().isBlank()
                && command.limits().remainingTokenBudget() >= 64;
        String logicalCallId = reasoningLogicalCallId(run);
        MultiAgentOrchestrationRequest request = new MultiAgentOrchestrationRequest(
                MultiAgentOrchestrationRequest.CONTRACT_VERSION,
                requestId,
                run.id(),
                run.tenantId(),
                run.ownerId(),
                run.projectId() == null
                        ? MultiAgentOrchestrationRequest.Mode.CHAT
                        : MultiAgentOrchestrationRequest.Mode.PROJECT,
                run.projectId(),
                effectiveTaskId(run),
                run.taskPlanId(),
                run.conversationId(),
                agentRefs,
                command.modelPoolRef(),
                command.context(),
                command.capabilities(),
                new MultiAgentOrchestrationRequest.Cursor(
                        externalPhase(run.executionCursor().phase()),
                        run.executionCursor().checkpointId(),
                        run.planStepId(),
                        effectiveTaskId(run)),
                command.limits(),
                useProviderReasoning
                        ? new MultiAgentOrchestrationRequest.Reasoning(
                                MultiAgentOrchestrationRequest.ReasoningMode.PROVIDER,
                                0, logicalCallId, null)
                        : null);

        MultiAgentOrchestrationResponse response = orchestrationPort.orchestrate(request);
        validateCorrelation(request, response);
        if (response.command().kind()
                == MultiAgentOrchestrationResponse.CommandKind.MODEL_REQUESTED) {
            if (!useProviderReasoning) {
                throw new BusinessException(
                        "Multi-Agent model request was not authorized",
                        HttpStatus.BAD_GATEWAY,
                        "MULTI_AGENT_MODEL_REQUEST_NOT_AUTHORIZED");
            }
            validateModelRequestScope(request, response.command());
            recordAccepted(run.id(), response);
            if (reasoningService == null) {
                throw new BusinessException(
                        "Provider reasoning is not configured",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "MULTI_AGENT_REASONING_NOT_CONFIGURED");
            }
            MultiAgentOrchestrationRequest.ReasoningResult result = reasoningService.execute(
                    run, response.command(), command.limits().remainingTokenBudget());
            request = withReasoningResult(request, result);
            response = orchestrationPort.orchestrate(request);
            validateCorrelation(request, response);
            if (response.command().kind()
                    == MultiAgentOrchestrationResponse.CommandKind.MODEL_REQUESTED) {
                throw new BusinessException(
                        "Multi-Agent reasoning requested more than one model boundary",
                        HttpStatus.BAD_GATEWAY,
                        "MULTI_AGENT_REASONING_RECURSION_REJECTED");
            }
        }
        validateCommandScope(run, response.command(), agentRefs, command.context());
        if (supervisorPolicy.recordsShadowComparison()) {
            recordShadowComparison(
                    command, run, request, response, agentRefs, logicalCallId);
        }
        dispatchAcceptedProposal(command, run, response.command(), agentRefs);

        runtimeApi.advanceCursor(new AdvanceExecutionCursorCommand(
                run.id(), routePhase(response.orchestration().route()), run.planStepId()));
        recordAccepted(run.id(), response);
        return response;
    }

    private void recordShadowComparison(
            InvokeMultiAgentOrchestrationCommand command,
            AgentRunView run,
            MultiAgentOrchestrationRequest deterministicRequest,
            MultiAgentOrchestrationResponse deterministicResponse,
            List<MultiAgentOrchestrationRequest.AgentRef> agentRefs,
            String logicalCallId) {
        String providerKind = null;
        String providerInputHash = null;
        boolean providerUnknown = false;
        try {
            if (reasoningService == null
                    || command.modelPoolRef() == null
                    || command.modelPoolRef().isBlank()
                    || command.limits().remainingTokenBudget() < 64) {
                throw new IllegalStateException("Provider shadow reasoning is unavailable");
            }
            MultiAgentOrchestrationRequest shadowRequest = withProviderReasoning(
                    deterministicRequest, logicalCallId);
            MultiAgentOrchestrationResponse modelRequest =
                    orchestrationPort.orchestrate(shadowRequest);
            validateCorrelation(shadowRequest, modelRequest);
            providerKind = modelRequest.command().kind().name();
            if (modelRequest.command().kind()
                    != MultiAgentOrchestrationResponse.CommandKind.MODEL_REQUESTED) {
                throw new IllegalStateException(
                        "Provider shadow did not request its model boundary");
            }
            validateModelRequestScope(shadowRequest, modelRequest.command());
            MultiAgentOrchestrationRequest.ReasoningResult reasoningResult =
                    reasoningService.execute(
                            run, modelRequest.command(),
                            command.limits().remainingTokenBudget());
            MultiAgentOrchestrationRequest candidateRequest =
                    withReasoningResult(shadowRequest, reasoningResult);
            MultiAgentOrchestrationResponse candidate =
                    orchestrationPort.orchestrate(candidateRequest);
            validateCorrelation(candidateRequest, candidate);
            providerKind = candidate.command().kind().name();
            if (candidate.command().kind()
                    == MultiAgentOrchestrationResponse.CommandKind.MODEL_REQUESTED) {
                throw new IllegalStateException(
                        "Provider shadow requested more than one model boundary");
            }
            validateCommandScope(run, candidate.command(), agentRefs, command.context());
            providerInputHash = commandInputHash(candidate.command());
        } catch (RuntimeException error) {
            providerUnknown = true;
        }

        supervisorShadowTelemetry.record(
                SupervisorPolicyMode.SHADOW,
                SupervisorShadowComparison.classify(
                        deterministicResponse.command().kind().name(),
                        commandInputHash(deterministicResponse.command()),
                        providerKind,
                        providerInputHash,
                        providerUnknown));
    }

    private static MultiAgentOrchestrationRequest withProviderReasoning(
            MultiAgentOrchestrationRequest request,
            String logicalCallId) {
        return new MultiAgentOrchestrationRequest(
                request.contractVersion(), request.requestId(), request.agentRunId(),
                request.organizationId(), request.userId(), request.mode(), request.projectId(),
                request.taskId(), request.taskPlanId(), request.conversationId(),
                request.agentRefs(), request.modelPoolRef(), request.context(),
                request.capabilities(), request.cursor(), request.limits(),
                new MultiAgentOrchestrationRequest.Reasoning(
                        MultiAgentOrchestrationRequest.ReasoningMode.PROVIDER,
                        0, logicalCallId, null));
    }

    private String commandInputHash(
            MultiAgentOrchestrationResponse.Command command) {
        try {
            byte[] canonical = objectMapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsBytes(Map.of(
                            "kind", command.kind().name(),
                            "payload", command.payload()));
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Unable to hash Supervisor command candidate", error);
        }
    }

    private SupervisorPolicy resolveSupervisorPolicy(AgentRunView run){if(supervisorPolicies==null)return new SupervisorPolicy(providerReasoningEnabled?SupervisorPolicyMode.OPT_IN:SupervisorPolicyMode.DETERMINISTIC,false,providerReasoningEnabled,hashPolicy("legacy:"+providerReasoningEnabled),java.time.Instant.EPOCH);var existing=supervisorPolicies.find(run.id()).orElse(null);if(existing!=null){if(!existing.tenantId().equals(run.tenantId())||!existing.ownerId().equals(run.ownerId()))throw new BusinessException("Supervisor policy scope mismatch",HttpStatus.CONFLICT,"SUPERVISOR_POLICY_SCOPE_MISMATCH");return SupervisorPolicyResolution.forRunningRun(existing.policy(),false).policy();}boolean provider=supervisorMode==SupervisorPolicyMode.OPT_IN||supervisorMode==SupervisorPolicyMode.SELECTED_DEFAULT;var requested=new SupervisorPolicy(supervisorMode,false,provider,hashPolicy(supervisorMode.name()+":"+provider),supervisorTime.now());var resolved=SupervisorPolicyResolution.forNewRun(requested,supervisorKillSwitch,false).policy();var pinned=new SupervisorRunPolicy(run.id(),run.tenantId(),run.ownerId(),resolved,1);if(!supervisorPolicies.insertIfAbsent(pinned)){var winner=supervisorPolicies.find(run.id()).orElseThrow();if(!winner.policy().policyHash().equals(resolved.policyHash()))return SupervisorPolicyResolution.forRunningRun(winner.policy(),false).policy();}return resolved;}
    private static String hashPolicy(String value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}

    private static MultiAgentOrchestrationRequest withReasoningResult(
            MultiAgentOrchestrationRequest request,
            MultiAgentOrchestrationRequest.ReasoningResult result) {
        return new MultiAgentOrchestrationRequest(
                request.contractVersion(), request.requestId(), request.agentRunId(),
                request.organizationId(), request.userId(), request.mode(), request.projectId(),
                request.taskId(), request.taskPlanId(), request.conversationId(),
                request.agentRefs(), request.modelPoolRef(), request.context(),
                request.capabilities(), request.cursor(), request.limits(),
                new MultiAgentOrchestrationRequest.Reasoning(
                        MultiAgentOrchestrationRequest.ReasoningMode.PROVIDER,
                        1, request.reasoning().logicalCallId(), result));
    }

    private static void validateCorrelation(
            MultiAgentOrchestrationRequest request,
            MultiAgentOrchestrationResponse response) {
        if (!request.requestId().equals(response.requestId())
                || !request.agentRunId().equals(response.agentRunId())) {
            throw new BusinessException(
                    "Multi-Agent response correlation does not match the request",
                    HttpStatus.BAD_GATEWAY,
                    "MULTI_AGENT_CORRELATION_MISMATCH");
        }
    }

    private static void validateModelRequestScope(
            MultiAgentOrchestrationRequest request,
            MultiAgentOrchestrationResponse.Command command) {
        requirePayloadMatch(request.modelPoolRef(), command.payload().get("modelPoolRef"));
        requirePayloadMatch(
                request.reasoning().logicalCallId(), command.payload().get("logicalCallId"));
    }

    private void recordAccepted(String runId, MultiAgentOrchestrationResponse response) {
        try {
            runtimeApi.recordEvent(new RecordRunEventCommand(
                    runId, RunEventType.ORCHESTRATION_COMMAND_ACCEPTED,
                    objectMapper.writeValueAsString(eventEvidence(response))));
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Unable to persist accepted Multi-Agent command", error);
        }
    }

    private static Object eventEvidence(MultiAgentOrchestrationResponse response) {
        if (response.command().kind()
                != MultiAgentOrchestrationResponse.CommandKind.MODEL_REQUESTED) {
            return response;
        }
        return Map.of(
                "contractVersion", response.contractVersion(),
                "requestId", response.requestId(),
                "agentRunId", response.agentRunId(),
                "command", Map.of(
                        "kind", "MODEL_REQUESTED",
                        "modelPoolRef", response.command().payload().get("modelPoolRef"),
                        "logicalCallId", response.command().payload().get("logicalCallId")),
                "orchestration", response.orchestration());
    }

    private void dispatchAcceptedProposal(
            InvokeMultiAgentOrchestrationCommand request,
            AgentRunView run,
            MultiAgentOrchestrationResponse.Command command,
            List<MultiAgentOrchestrationRequest.AgentRef> agentRefs) {
        if (command.kind() == MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED
                && run.chatTaskId() != null) {
            persistChatPlan(run, command);
        }
        if (collaborationApi == null || request.sourceRepositoryId() == null
                || command.kind() != MultiAgentOrchestrationResponse.CommandKind.DELEGATE_SUBTASK) {
            return;
        }
        String agentId = (String) command.payload().get("preferredAgentId");
        MultiAgentOrchestrationRequest.AgentRef target = agentRefs.stream()
                .filter(ref -> ref.agentId().equals(agentId))
                .filter(ref -> !ref.role().equals("supervisor"))
                .findFirst()
                .orElseThrow(() -> scopeMismatch());
        collaborationApi.delegate(new MultiAgentCollaborationApplicationApi.DelegateCommand(
                run.ownerId(), run.id(), target.agentId(),
                request.sourceRepositoryId(), request.workspaceBaseRef()));
    }

    @SuppressWarnings("unchecked")
    private void persistChatPlan(
            AgentRunView run,
            MultiAgentOrchestrationResponse.Command command) {
        if (taskPlanApi == null) {
            throw new BusinessException(
                    "Chat TaskPlan persistence is unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CHAT_TASK_PLAN_PERSISTENCE_UNAVAILABLE");
        }
        List<Map<String, Object>> rawSteps =
                (List<Map<String, Object>>) command.payload().get("steps");
        List<CreateChatTaskPlanProposalCommand.StepProposal> steps = rawSteps.stream()
                .map(step -> new CreateChatTaskPlanProposalCommand.StepProposal(
                        (String) step.get("stepKey"),
                        (String) step.get("goal"),
                        (List<String>) step.get("dependsOnStepKeys")))
                .toList();
        taskPlanApi.createChatProposal(new CreateChatTaskPlanProposalCommand(
                run.tenantId(), run.ownerId(), run.conversationId(), run.chatTaskId(),
                run.id(), null,
                (String) command.payload().get("strategySummary"), steps, run.agentId()));
    }

    private static void validateCommandScope(
            AgentRunView run,
            MultiAgentOrchestrationResponse.Command command,
            List<MultiAgentOrchestrationRequest.AgentRef> agentRefs,
            MultiAgentOrchestrationRequest.Context context) {
        if (command.kind() == MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED) {
            requirePayloadMatch(effectiveTaskId(run), command.payload().get("rootTaskId"));
        }
        if (command.kind() == MultiAgentOrchestrationResponse.CommandKind.DELEGATE_SUBTASK) {
            requireTaskScope(run);
            requirePayloadMatch(run.taskPlanId(), command.payload().get("taskPlanId"));
            requirePayloadMatch(run.planStepId(), command.payload().get("planStepId"));
            requirePayloadMatch(run.taskId(), command.payload().get("childTaskId"));
            Object preferredAgentId = command.payload().get("preferredAgentId");
            if (!(preferredAgentId instanceof String agentId)
                    || agentRefs.stream().noneMatch(ref ->
                            ref.agentId().equals(agentId) && !ref.role().equals("supervisor"))) {
                throw scopeMismatch();
            }
        }
        if (command.kind() == MultiAgentOrchestrationResponse.CommandKind.APPROVAL_REQUIRED) {
            String scopeType = (String) command.payload().get("scopeType");
            String expected = "TASK_PLAN".equals(scopeType)
                    ? run.taskPlanId()
                    : run.planStepId();
            if (expected != null) {
                requirePayloadMatch(expected, command.payload().get("scopeId"));
            }
        }
        if (command.kind() == MultiAgentOrchestrationResponse.CommandKind.HANDOFF_PROPOSED) {
            requirePayloadMatch(run.id(), command.payload().get("sourceAgentRunId"));
            String targetAgentId = (String) command.payload().get("targetAgentId");
            if (agentRefs.stream().noneMatch(ref ->
                    ref.agentId().equals(targetAgentId)
                            && !ref.role().equals("supervisor"))) {
                throw scopeMismatch();
            }
        }
        if (command.kind() == MultiAgentOrchestrationResponse.CommandKind.REVIEW_REQUIRED) {
            requireTaskScope(run);
            requirePayloadMatch(run.taskPlanId(), command.payload().get("taskPlanId"));
            requirePayloadMatch(run.planStepId(), command.payload().get("planStepId"));
            @SuppressWarnings("unchecked")
            List<String> artifactIds = (List<String>) command.payload().get("artifactIds");
            Set<String> allowedArtifacts = context.sources().stream()
                    .filter(source -> source.type().equals("ARTIFACT"))
                    .map(MultiAgentOrchestrationRequest.ContextSource::sourceId)
                    .collect(Collectors.toSet());
            if (!allowedArtifacts.containsAll(artifactIds)) {
                throw scopeMismatch();
            }
        }
    }

    private static void requireTaskScope(AgentRunView run) {
        if (run.taskPlanId() == null || run.planStepId() == null || run.taskId() == null) {
            throw scopeMismatch();
        }
    }

    private static void requirePayloadMatch(String expected, Object actual) {
        if (!expected.equals(actual)) {
            throw scopeMismatch();
        }
    }

    private static BusinessException scopeMismatch() {
        return new BusinessException(
                "Multi-Agent command references state outside the AgentRun scope",
                HttpStatus.BAD_GATEWAY,
                "MULTI_AGENT_COMMAND_SCOPE_MISMATCH");
    }

    private static String externalPhase(String phase) {
        if (phase == null) {
            return "planning";
        }
        String normalized = phase.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("complete")) {
            return "complete";
        }
        if (normalized.contains("approval") || normalized.contains("waiting")) {
            return "awaiting_approval";
        }
        if (normalized.contains("execute") || normalized.contains("tool")
                || normalized.contains("compile")) {
            return "execute";
        }
        return "planning";
    }

    private static String reasoningLogicalCallId(AgentRunView run) {
        String scope = run.executionCursor().checkpointId();
        if (scope == null || scope.isBlank()) {
            scope = run.planStepId();
        }
        if (scope == null || scope.isBlank()) {
            scope = effectiveTaskId(run);
        }
        if (scope == null || scope.isBlank()) {
            scope = "root";
        }
        return "multi-agent:supervisor:"
                + externalPhase(run.executionCursor().phase()) + ":" + scope;
    }

    private static String effectiveTaskId(AgentRunView run) {
        return run.chatTaskId() == null ? run.taskId() : run.chatTaskId();
    }

    private static String routePhase(String route) {
        return switch (route) {
            case "planner" -> "planning";
            case "delegate" -> "execute";
            case "handoff" -> "handoff";
            case "review" -> "review";
            case "approval" -> "awaiting_approval";
            case "complete" -> "complete";
            default -> throw new IllegalArgumentException("Unsupported route: " + route);
        };
    }
}
