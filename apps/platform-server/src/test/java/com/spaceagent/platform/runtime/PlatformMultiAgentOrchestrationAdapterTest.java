package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.InvokeMultiAgentOrchestrationCommand;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.application.MultiAgentOrchestrationApplicationService;
import com.spaceagent.platform.runtime.application.ProviderBackedSupervisorReasoningService;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.domain.MultiAgentOrchestrationPort;
import com.spaceagent.platform.runtime.domain.MultiAgentOrchestrationUnavailableException;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.domain.SupervisorPolicyMode;
import com.spaceagent.platform.runtime.domain.SupervisorRunPolicyRepository;
import com.spaceagent.platform.runtime.domain.SupervisorShadowComparison;
import com.spaceagent.platform.runtime.domain.SupervisorShadowTelemetry;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;
import com.spaceagent.platform.runtime.infrastructure.HttpMultiAgentOrchestrationClient;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryRuntimeLedgerRepository;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.InferenceBudgetApplicationApi;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.inference.api.ModelPoolResolutionView;
import com.spaceagent.platform.inference.api.ResolvedModelCandidateView;
import com.spaceagent.platform.inference.domain.ModelPoolRoutingStrategy;
import com.spaceagent.platform.inference.application.InferenceExecutionService;
import com.spaceagent.platform.inference.application.ModelCallLedgerService;
import com.spaceagent.platform.inference.application.ModelCallPayloadCodec;
import com.spaceagent.platform.inference.application.ModelCallRequestHasher;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import com.spaceagent.platform.inference.domain.ModelCallLeasePolicy;
import com.spaceagent.platform.inference.infrastructure.memory.InMemoryModelCallLedgerRepository;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.project.api.CreateChatTaskPlanProposalCommand;
import com.spaceagent.platform.project.api.TaskExecutionApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PlatformMultiAgentOrchestrationAdapterTest {

    @Test
    void chatPlanProposalUsesPinnedRootTaskAndPersistsThroughJavaOwnerApi() {
        ObjectMapper objectMapper = new ObjectMapper();
        TaskExecutionApplicationApi taskExecution = mock(TaskExecutionApplicationApi.class);
        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(),
                mock(ToolExecutionLedgerApplicationApi.class), objectMapper,
                () -> UUID.randomUUID().toString(),
                () -> Instant.parse("2026-09-06T14:00:00Z"),
                null, null, taskExecution);
        String rootTaskId = UUID.randomUUID().toString();
        var run = runtime.startRun(new StartAgentRunCommand(
                "org-1", "user-1", "agent-1", "version-1", "conversation-1",
                rootTaskId, null, null, null, null, null, null));
        MultiAgentOrchestrationPort planner = request -> {
            assertThat(request.mode()).isEqualTo(MultiAgentOrchestrationRequest.Mode.CHAT);
            assertThat(request.taskId()).isEqualTo(rootTaskId);
            return new MultiAgentOrchestrationResponse(
                    "multi-agent/v1", request.requestId(), request.agentRunId(),
                    new MultiAgentOrchestrationResponse.Command(
                            MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED,
                            Map.of(
                                    "rootTaskId", rootTaskId,
                                    "strategySummary", "Collect then synthesize",
                                    "steps", List.of(
                                            Map.of("stepKey", "collect", "goal", "Collect",
                                                    "dependsOnStepKeys", List.of()),
                                            Map.of("stepKey", "synthesize", "goal", "Synthesize",
                                                    "dependsOnStepKeys", List.of("collect"))))),
                    new MultiAgentOrchestrationResponse.Orchestration("planner", true));
        };
        TaskPlanApplicationApi plans = mock(TaskPlanApplicationApi.class);
        var service = new MultiAgentOrchestrationApplicationService(
                runtime, planner, () -> "request-chat-plan", objectMapper,
                null, null, false, plans);

        service.invoke(new InvokeMultiAgentOrchestrationCommand(
                run.id(), List.of(), null, context(), capabilities(), limits()));

        ArgumentCaptor<CreateChatTaskPlanProposalCommand> captured =
                ArgumentCaptor.forClass(CreateChatTaskPlanProposalCommand.class);
        verify(plans).createChatProposal(captured.capture());
        assertThat(captured.getValue().rootTaskId()).isEqualTo(rootTaskId);
        assertThat(captured.getValue().sourceAgentRunId()).isEqualTo(run.id());
        assertThat(captured.getValue().generatedByConfigurationHash()).isNull();
        assertThat(captured.getValue().generatedByAgentId()).isEqualTo("agent-1");
        assertThat(captured.getValue().steps()).hasSize(2);
    }

    @Test
    void providerReasoningUsesOneJavaOwnedLedgeredModelBoundary() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger ids = new AtomicInteger();
        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(),
                mock(ToolExecutionLedgerApplicationApi.class), objectMapper,
                () -> "id-" + ids.incrementAndGet(),
                () -> Instant.parse("2026-08-23T12:00:00Z"));
        var run = runtime.startRun(new StartAgentRunCommand(
                "org-1", "user-1", "agent-1", "version-1", "conversation-1",
                null, null, null, null));
        ModelPoolApplicationApi pools = mock(ModelPoolApplicationApi.class);
        when(pools.resolvePool(
                "org-1", "user-1", "pool-1", "multi-agent:supervisor:planning:root"))
                .thenReturn(new ModelPoolResolutionView(
                        "pool-1",
                        List.of(new ResolvedModelCandidateView(
                                "member-1", "provider-1", "provider-model-1", "model-1",
                                0, 1, 25, "price-1", 1000L, 2000L)),
                        ModelPoolRoutingStrategy.PRIORITY,
                        false,
                        "sha256:snapshot"));
        InferenceExecutionApi inference = mock(InferenceExecutionApi.class);
        when(inference.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new InferenceExecutionApi.InferenceExecutionResult(
                        "{\"route\":\"complete\",\"rationale\":\"Provider selected completion\","
                                + "\"preferredAgentId\":null,\"strategySummary\":null,\"steps\":[]}",
                        120, 40, List.of(), "stop", "provider-request-1", Map.of(),
                        "provider-1", "model-1", 1, "sha256:snapshot"));
        ProviderReasoningPort port = new ProviderReasoningPort();
        var service = new MultiAgentOrchestrationApplicationService(
                runtime, port, () -> "request-provider", objectMapper,
                null, new ProviderBackedSupervisorReasoningService(
                        runtime, pools, inference), true);

        MultiAgentOrchestrationResponse response = service.invoke(
                new InvokeMultiAgentOrchestrationCommand(
                        run.id(), List.of(), "pool-1", context(), capabilities(), limits()));

        assertThat(response.command().kind())
                .isEqualTo(MultiAgentOrchestrationResponse.CommandKind.COMPLETED);
        assertThat(response.command().payload().get("summary"))
                .isEqualTo("Provider selected completion");
        assertThat(port.invocations).isEqualTo(2);
        verify(inference).execute(org.mockito.ArgumentMatchers.argThat(command ->
                command.agentRunId().equals(run.id())
                        && command.logicalCallId().equals("multi-agent:supervisor:planning:root")
                        && command.modelPoolId().equals("pool-1")
                        && command.candidateSnapshotHash().equals("sha256:snapshot")));
        assertThat(runtime.findSteps(run.id())).singleElement()
                .satisfies(step -> {
                    assertThat(step.type()).isEqualTo("multi-agent-supervisor-reasoning");
                    assertThat(step.state().name()).isEqualTo("COMPLETED");
                });
        assertThat(runtime.findEvents(run.id()))
                .extracting(event -> event.type())
                .containsSubsequence(
                        RunEventType.ORCHESTRATION_COMMAND_ACCEPTED,
                        RunEventType.STEP_STARTED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.CURSOR_ADVANCED,
                        RunEventType.ORCHESTRATION_COMMAND_ACCEPTED);
        assertThat(runtime.findEvents(run.id()).stream()
                .filter(event -> event.type() == RunEventType.ORCHESTRATION_COMMAND_ACCEPTED)
                .map(event -> event.payload())
                .toList())
                .noneMatch(payload -> payload.contains("Return JSON")
                        || payload.contains("Choose a route"));
    }

    @Test
    void providerReasoningReplaysCompletedModelCallAfterOrchestratorCrash() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger ids = new AtomicInteger();
        AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-08-23T13:00:00Z"));
        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(),
                mock(ToolExecutionLedgerApplicationApi.class), objectMapper,
                () -> "replay-id-" + ids.incrementAndGet(), now::get);
        var run = runtime.startRun(new StartAgentRunCommand(
                "org-1", "user-1", "agent-1", "version-1", "conversation-1",
                null, null, null, null));
        ModelPoolApplicationApi pools = mock(ModelPoolApplicationApi.class);
        when(pools.resolvePool(
                "org-1", "user-1", "pool-1", "multi-agent:supervisor:planning:root"))
                .thenReturn(new ModelPoolResolutionView(
                        "pool-1",
                        List.of(new ResolvedModelCandidateView(
                                "member-1", "provider-1", "provider-model-1", "model-1",
                                0, 1, 25, null, null, null)),
                        ModelPoolRoutingStrategy.PRIORITY, false, "sha256:stable"));
        AtomicInteger providerCalls = new AtomicInteger();
        InferenceExecutor executor = request -> {
            providerCalls.incrementAndGet();
            return new InferenceExecutor.InferenceExecution(
                    "{\"route\":\"complete\",\"rationale\":\"Replay-safe completion\","
                            + "\"preferredAgentId\":null,\"strategySummary\":null,\"steps\":[]}",
                    10, 5);
        };
        var ledger = new ModelCallLedgerService(
                new InMemoryModelCallLedgerRepository(now::get),
                () -> UUID.randomUUID().toString(), () -> "worker-1");
        ModelCallLeasePolicy leasePolicy = new ModelCallLeasePolicy() {
            @Override public long requestTimeoutSeconds() { return 3; }
            @Override public long claimLeaseSeconds() { return 5; }
        };
        InferenceExecutionApi inference = new InferenceExecutionService(
                executor, ledger, new ModelCallRequestHasher(objectMapper),
                new ModelCallPayloadCodec(objectMapper), leasePolicy, null, com.spaceagent.platform.inference.domain.InferenceTelemetry.noop(), (t, r) -> {});
        CrashOnceProviderReasoningPort port = new CrashOnceProviderReasoningPort();
        var service = new MultiAgentOrchestrationApplicationService(
                runtime, port, () -> "request-replay", objectMapper,
                null, new ProviderBackedSupervisorReasoningService(
                        runtime, pools, inference), true);
        var command = new InvokeMultiAgentOrchestrationCommand(
                run.id(), List.of(), "pool-1", context(), capabilities(), limits());

        assertThatThrownBy(() -> service.invoke(command))
                .isInstanceOf(MultiAgentOrchestrationUnavailableException.class);
        assertThat(service.invoke(command).command().payload().get("summary"))
                .isEqualTo("Replay-safe completion");
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void shadowUsesLedgeredProviderCandidateWithoutDispatchingItsProposal() {
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(),
                mock(ToolExecutionLedgerApplicationApi.class), objectMapper,
                () -> UUID.randomUUID().toString(),
                () -> Instant.parse("2026-09-09T08:00:00Z"),
                null, null, mock(TaskExecutionApplicationApi.class));
        String rootTaskId = UUID.randomUUID().toString();
        var run = runtime.startRun(new StartAgentRunCommand(
                "org-1", "user-1", "agent-1", "version-1", "conversation-1",
                rootTaskId, null, null, null, null, null, null));
        AtomicInteger providerCalls = new AtomicInteger();
        InferenceBudgetApplicationApi budget = mock(InferenceBudgetApplicationApi.class);
        when(budget.reserve(any())).thenReturn(
                new InferenceBudgetApplicationApi.ReservationView(
                        "reservation-shadow", "RESERVED", true, 1L));
        var ledger = new ModelCallLedgerService(
                new InMemoryModelCallLedgerRepository(
                        () -> Instant.parse("2026-09-09T08:00:00Z")),
                () -> UUID.randomUUID().toString(),
                () -> "shadow-worker");
        ModelCallLeasePolicy leasePolicy = new ModelCallLeasePolicy() {
            @Override public long requestTimeoutSeconds() { return 3; }
            @Override public long claimLeaseSeconds() { return 5; }
        };
        InferenceExecutionApi inference = new InferenceExecutionService(
                request -> {
                    providerCalls.incrementAndGet();
                    return new InferenceExecutor.InferenceExecution(
                            providerResult().content(), 120, 40);
                },
                ledger,
                new ModelCallRequestHasher(objectMapper),
                new ModelCallPayloadCodec(objectMapper),
                leasePolicy,
                budget, com.spaceagent.platform.inference.domain.InferenceTelemetry.noop(), (t, r) -> {});
        TaskPlanApplicationApi plans = mock(TaskPlanApplicationApi.class);
        ShadowProviderPort port = new ShadowProviderPort(rootTaskId);
        RecordingShadowTelemetry telemetry = new RecordingShadowTelemetry();
        var service = new MultiAgentOrchestrationApplicationService(
                runtime, port, () -> "request-shadow", objectMapper,
                null, shadowReasoning(runtime, inference, rootTaskId), false, plans);
        configureShadow(service, run.id());
        service.configureSupervisorShadowTelemetry(telemetry);

        MultiAgentOrchestrationResponse response = service.invoke(
                new InvokeMultiAgentOrchestrationCommand(
                        run.id(), List.of(), "pool-1", context(), capabilities(), limits()));

        assertThat(response.command().kind())
                .isEqualTo(MultiAgentOrchestrationResponse.CommandKind.COMPLETED);
        assertThat(response.command().payload().get("summary")).isEqualTo("deterministic");
        assertThat(port.invocations).isEqualTo(3);
        assertThat(providerCalls).hasValue(1);
        assertThat(ledger.findByLogicalCall(
                run.id(), "multi-agent:supervisor:planning:" + rootTaskId)).isPresent();
        verify(budget).reserve(any());
        verify(budget).settle("reservation-shadow", 120, 40);
        verifyNoInteractions(plans);
        assertThat(telemetry.comparison.outcome())
                .isEqualTo(SupervisorShadowComparison.Outcome.COMMAND_KIND_MISMATCH);
        assertThat(telemetry.mode).isEqualTo(SupervisorPolicyMode.SHADOW);
        assertThat(runtime.findEvents(run.id()).stream()
                .filter(event -> event.type() == RunEventType.ORCHESTRATION_COMMAND_ACCEPTED))
                .hasSize(1);
        assertThat(runtime.findRun(run.id()).orElseThrow().executionCursor().phase())
                .isEqualTo("complete");
    }

    @Test
    void shadowProviderFailureRecordsUnknownAndLeavesMainDecisionUntouched() {
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(),
                mock(ToolExecutionLedgerApplicationApi.class), objectMapper,
                () -> UUID.randomUUID().toString(),
                () -> Instant.parse("2026-09-09T08:05:00Z"),
                null, null, mock(TaskExecutionApplicationApi.class));
        String rootTaskId = UUID.randomUUID().toString();
        var run = runtime.startRun(new StartAgentRunCommand(
                "org-1", "user-1", "agent-1", "version-1", "conversation-1",
                rootTaskId, null, null, null, null, null, null));
        InferenceExecutionApi inference = mock(InferenceExecutionApi.class);
        when(inference.execute(any())).thenThrow(new IllegalStateException("provider unknown"));
        ShadowProviderPort port = new ShadowProviderPort(rootTaskId);
        RecordingShadowTelemetry telemetry = new RecordingShadowTelemetry();
        var service = new MultiAgentOrchestrationApplicationService(
                runtime, port, () -> "request-shadow-unknown", objectMapper,
                null, shadowReasoning(runtime, inference, rootTaskId), false, null);
        configureShadow(service, run.id());
        service.configureSupervisorShadowTelemetry(telemetry);

        MultiAgentOrchestrationResponse response = service.invoke(
                new InvokeMultiAgentOrchestrationCommand(
                        run.id(), List.of(), "pool-1", context(), capabilities(), limits()));

        assertThat(response.command().payload().get("summary")).isEqualTo("deterministic");
        assertThat(telemetry.comparison.outcome())
                .isEqualTo(SupervisorShadowComparison.Outcome.UNKNOWN_CANDIDATE);
        assertThat(runtime.findRun(run.id()).orElseThrow().executionCursor().phase())
                .isEqualTo("complete");
    }

    @Test
    void applicationBuildsRunOwnedRequestAndPersistsAcceptedCommand() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger ids = new AtomicInteger();
        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(),
                mock(ToolExecutionLedgerApplicationApi.class), objectMapper,
                () -> "id-" + ids.incrementAndGet(),
                () -> Instant.parse("2026-08-22T23:00:00Z"));
        var run = runtime.startRun(new StartAgentRunCommand(
                "org-1", "user-1", "agent-1", "version-1", "conversation-1",
                null, null, null, null));

        RecordingPort port = new RecordingPort();
        MultiAgentOrchestrationApplicationService service =
                new MultiAgentOrchestrationApplicationService(
                        runtime, port, () -> "request-1", objectMapper);
        var response = service.invoke(new InvokeMultiAgentOrchestrationCommand(
                run.id(), List.of(), "pool-1", context(), capabilities(), limits()));

        assertThat(response.command().kind())
                .isEqualTo(MultiAgentOrchestrationResponse.CommandKind.APPROVAL_REQUIRED);
        assertThat(port.request.organizationId()).isEqualTo("org-1");
        assertThat(port.request.userId()).isEqualTo("user-1");
        assertThat(port.request.mode()).isEqualTo(MultiAgentOrchestrationRequest.Mode.CHAT);
        assertThat(port.request.agentRefs()).singleElement()
                .satisfies(ref -> assertThat(ref.role()).isEqualTo("supervisor"));
        assertThat(runtime.findRun(run.id()).orElseThrow().executionCursor().phase())
                .isEqualTo("awaiting_approval");
        assertThat(runtime.findEvents(run.id()))
                .extracting(event -> event.type())
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CURSOR_ADVANCED,
                        RunEventType.ORCHESTRATION_COMMAND_ACCEPTED);
    }

    @Test
    void httpAdapterUsesVersionedEndpointAndRejectsUnknownResponseFields() {
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpMultiAgentOrchestrationClient client =
                new HttpMultiAgentOrchestrationClient(builder, objectMapper, "http://orchestrator");
        MultiAgentOrchestrationRequest request = request();

        server.expect(requestTo("http://orchestrator/v1/orchestrate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                        "traceparent",
                        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"))
                .andRespond(withSuccess("""
                        {
                          "contractVersion":"multi-agent/v1",
                          "requestId":"request-1",
                          "agentRunId":"run-1",
                          "command":{"kind":"COMPLETED","payload":{"summary":"done"}},
                          "orchestration":{"route":"complete","ephemeral":true}
                        }
                        """, MediaType.APPLICATION_JSON));
        var parent = Span.wrap(SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault()));
        try (var ignored = Context.current().with(parent).makeCurrent()) {
            assertThat(client.orchestrate(request).command().kind())
                    .isEqualTo(MultiAgentOrchestrationResponse.CommandKind.COMPLETED);
        }
        server.verify();

        RestClient.Builder invalidBuilder = RestClient.builder();
        MockRestServiceServer invalidServer = MockRestServiceServer.bindTo(invalidBuilder).build();
        HttpMultiAgentOrchestrationClient invalidClient =
                new HttpMultiAgentOrchestrationClient(
                        invalidBuilder, objectMapper, "http://orchestrator");
        invalidServer.expect(requestTo("http://orchestrator/v1/orchestrate"))
                .andRespond(withSuccess("""
                        {
                          "contractVersion":"multi-agent/v1",
                          "requestId":"request-1",
                          "agentRunId":"run-1",
                          "command":{"kind":"COMPLETED","payload":{"summary":"done"}},
                          "orchestration":{"route":"complete","ephemeral":true},
                          "unexpected":true
                        }
                        """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> invalidClient.orchestrate(request))
                .isInstanceOf(MultiAgentOrchestrationUnavailableException.class)
                .hasMessageContaining("encode or decode");
        invalidServer.verify();
    }

    @Test
    void applicationRejectsDelegateOutsideTheRunTaskScope() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger ids = new AtomicInteger();
        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(),
                mock(ToolExecutionLedgerApplicationApi.class), objectMapper,
                () -> "id-" + ids.incrementAndGet(),
                () -> Instant.parse("2026-08-22T23:00:00Z"));
        var run = runtime.startRun(new StartAgentRunCommand(
                "org-1", "user-1", "agent-1", "version-1", "conversation-1",
                null, null, null, null));
        MultiAgentOrchestrationPort delegate = request -> new MultiAgentOrchestrationResponse(
                "multi-agent/v1", request.requestId(), request.agentRunId(),
                new MultiAgentOrchestrationResponse.Command(
                        MultiAgentOrchestrationResponse.CommandKind.DELEGATE_SUBTASK,
                        mapWithNullablePreferredAgent()),
                new MultiAgentOrchestrationResponse.Orchestration("delegate", true));
        var service = new MultiAgentOrchestrationApplicationService(
                runtime, delegate, () -> "request-1", objectMapper);

        assertThatThrownBy(() -> service.invoke(new InvokeMultiAgentOrchestrationCommand(
                run.id(), List.of(), "pool-1", context(), capabilities(), limits())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MULTI_AGENT_COMMAND_SCOPE_MISMATCH"));
        assertThat(runtime.findEvents(run.id()))
                .extracting(event -> event.type())
                .containsExactly(RunEventType.RUN_CREATED);
    }

    private static Map<String, Object> mapWithNullablePreferredAgent() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskPlanId", "plan-other");
        payload.put("planStepId", "step-other");
        payload.put("childTaskId", "task-other");
        payload.put("preferredAgentId", null);
        return payload;
    }

    private static MultiAgentOrchestrationRequest request() {
        return new MultiAgentOrchestrationRequest(
                "multi-agent/v1", "request-1", "run-1", "org-1", "user-1",
                MultiAgentOrchestrationRequest.Mode.CHAT,
                null, null, null, "conversation-1",
                List.of(new MultiAgentOrchestrationRequest.AgentRef(
                        "agent-1", "run-1", "supervisor")),
                "pool-1", context(), capabilities(),
                new MultiAgentOrchestrationRequest.Cursor("planning", null, null, null),
                limits());
    }

    private static MultiAgentOrchestrationRequest.Context context() {
        return new MultiAgentOrchestrationRequest.Context(
                "context-1", 4096, List.of(new MultiAgentOrchestrationRequest.ContextSource(
                        "TASK", "task-1", "Goal", 100)));
    }

    private static MultiAgentOrchestrationRequest.Capabilities capabilities() {
        return new MultiAgentOrchestrationRequest.Capabilities(
                List.of(), List.of(), List.of());
    }

    private static MultiAgentOrchestrationRequest.Limits limits() {
        return new MultiAgentOrchestrationRequest.Limits(8, 2, 4, 3000);
    }

    private static ProviderBackedSupervisorReasoningService shadowReasoning(
            RuntimeApplicationService runtime,
            InferenceExecutionApi inference,
            String rootTaskId) {
        ModelPoolApplicationApi pools = mock(ModelPoolApplicationApi.class);
        when(pools.resolvePool(
                "org-1", "user-1", "pool-1",
                "multi-agent:supervisor:planning:" + rootTaskId))
                .thenReturn(new ModelPoolResolutionView(
                        "pool-1",
                        List.of(new ResolvedModelCandidateView(
                                "member-1", "provider-1", "provider-model-1", "model-1",
                                0, 1, 25, "price-1", 1000L, 2000L)),
                        ModelPoolRoutingStrategy.PRIORITY,
                        false,
                        "sha256:shadow"));
        return new ProviderBackedSupervisorReasoningService(runtime, pools, inference);
    }

    private static InferenceExecutionApi.InferenceExecutionResult providerResult() {
        return new InferenceExecutionApi.InferenceExecutionResult(
                "{\"route\":\"plan\",\"rationale\":\"shadow\","
                        + "\"preferredAgentId\":null,\"strategySummary\":\"candidate\","
                        + "\"steps\":[]}",
                120, 40, List.of(), "stop", "provider-request-shadow", Map.of(),
                "provider-1", "model-1", 1, "sha256:shadow");
    }

    private static void configureShadow(
            MultiAgentOrchestrationApplicationService service,
            String runId) {
        SupervisorRunPolicyRepository policies = mock(SupervisorRunPolicyRepository.class);
        when(policies.find(runId)).thenReturn(Optional.empty());
        when(policies.insertIfAbsent(any())).thenReturn(true);
        service.configureSupervisorPolicy(
                policies,
                () -> Instant.parse("2026-09-09T08:00:00Z"),
                "SHADOW",
                false);
    }

    private static final class RecordingPort implements MultiAgentOrchestrationPort {
        private MultiAgentOrchestrationRequest request;

        @Override
        public MultiAgentOrchestrationResponse orchestrate(
                MultiAgentOrchestrationRequest request) {
            this.request = request;
            return new MultiAgentOrchestrationResponse(
                    "multi-agent/v1", request.requestId(), request.agentRunId(),
                    new MultiAgentOrchestrationResponse.Command(
                            MultiAgentOrchestrationResponse.CommandKind.APPROVAL_REQUIRED,
                            Map.of(
                                    "scopeType", "PLAN_STEP",
                                    "scopeId", "step-1",
                                    "reason", "review")),
                    new MultiAgentOrchestrationResponse.Orchestration(
                            "approval", true));
        }
    }

    private static final class ProviderReasoningPort implements MultiAgentOrchestrationPort {
        private int invocations;

        @Override
        public MultiAgentOrchestrationResponse orchestrate(
                MultiAgentOrchestrationRequest request) {
            invocations++;
            if (invocations == 1) {
                assertThat(request.reasoning()).isNotNull();
                assertThat(request.reasoning().round()).isZero();
                return new MultiAgentOrchestrationResponse(
                        "multi-agent/v1", request.requestId(), request.agentRunId(),
                        new MultiAgentOrchestrationResponse.Command(
                                MultiAgentOrchestrationResponse.CommandKind.MODEL_REQUESTED,
                                Map.of(
                                        "modelPoolRef", request.modelPoolRef(),
                                        "logicalCallId", request.reasoning().logicalCallId(),
                                        "messages", List.of(
                                                Map.of("role", "system", "content", "Return JSON"),
                                                Map.of("role", "user", "content", "Choose a route")),
                                        "parameters", Map.of(
                                                "temperature", 0,
                                                "maxOutputTokens", 256,
                                                "responseFormat", "json_object"))),
                        new MultiAgentOrchestrationResponse.Orchestration("model", true));
            }
            assertThat(request.reasoning().round()).isOne();
            assertThat(request.reasoning().result().selectedProviderId())
                    .isEqualTo("provider-1");
            return new MultiAgentOrchestrationResponse(
                    "multi-agent/v1", request.requestId(), request.agentRunId(),
                    new MultiAgentOrchestrationResponse.Command(
                            MultiAgentOrchestrationResponse.CommandKind.COMPLETED,
                            Map.of("summary", "Provider selected completion")),
                    new MultiAgentOrchestrationResponse.Orchestration("complete", true));
        }
    }

    private static final class CrashOnceProviderReasoningPort
            implements MultiAgentOrchestrationPort {
        private int invocations;

        @Override
        public MultiAgentOrchestrationResponse orchestrate(
                MultiAgentOrchestrationRequest request) {
            invocations++;
            if (request.reasoning().round() == 0) {
                return new MultiAgentOrchestrationResponse(
                        "multi-agent/v1", request.requestId(), request.agentRunId(),
                        new MultiAgentOrchestrationResponse.Command(
                                MultiAgentOrchestrationResponse.CommandKind.MODEL_REQUESTED,
                                Map.of(
                                        "modelPoolRef", "pool-1",
                                        "logicalCallId", request.reasoning().logicalCallId(),
                                        "messages", List.of(
                                                Map.of("role", "system", "content", "Return JSON")),
                                        "parameters", Map.of(
                                                "temperature", 0,
                                                "maxOutputTokens", 128,
                                                "responseFormat", "json_object"))),
                        new MultiAgentOrchestrationResponse.Orchestration("model", true));
            }
            if (invocations == 2) {
                throw new MultiAgentOrchestrationUnavailableException(
                        "simulated crash after durable model completion");
            }
            return new MultiAgentOrchestrationResponse(
                    "multi-agent/v1", request.requestId(), request.agentRunId(),
                    new MultiAgentOrchestrationResponse.Command(
                            MultiAgentOrchestrationResponse.CommandKind.COMPLETED,
                            Map.of("summary", "Replay-safe completion")),
                    new MultiAgentOrchestrationResponse.Orchestration("complete", true));
        }
    }

    private static final class ShadowProviderPort implements MultiAgentOrchestrationPort {
        private final String rootTaskId;
        private int invocations;

        private ShadowProviderPort(String rootTaskId) {
            this.rootTaskId = rootTaskId;
        }

        @Override
        public MultiAgentOrchestrationResponse orchestrate(
                MultiAgentOrchestrationRequest request) {
            invocations++;
            if (request.reasoning() == null) {
                return response(
                        request,
                        MultiAgentOrchestrationResponse.CommandKind.COMPLETED,
                        Map.of("summary", "deterministic"),
                        "complete");
            }
            if (request.reasoning().round() == 0) {
                return response(
                        request,
                        MultiAgentOrchestrationResponse.CommandKind.MODEL_REQUESTED,
                        Map.of(
                                "modelPoolRef", request.modelPoolRef(),
                                "logicalCallId", request.reasoning().logicalCallId(),
                                "messages", List.of(
                                        Map.of("role", "system", "content", "Return JSON")),
                                "parameters", Map.of(
                                        "temperature", 0,
                                        "maxOutputTokens", 128,
                                        "responseFormat", "json_object")),
                        "model");
            }
            return response(
                    request,
                    MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED,
                    Map.of(
                            "rootTaskId", request.taskId(),
                            "strategySummary", "provider candidate",
                            "steps", List.of(Map.of(
                                    "stepKey", "shadow-observe",
                                    "goal", "Observe candidate only",
                                    "dependsOnStepKeys", List.of()))),
                    "planner");
        }

        private static MultiAgentOrchestrationResponse response(
                MultiAgentOrchestrationRequest request,
                MultiAgentOrchestrationResponse.CommandKind kind,
                Map<String, Object> payload,
                String route) {
            return new MultiAgentOrchestrationResponse(
                    "multi-agent/v1", request.requestId(), request.agentRunId(),
                    new MultiAgentOrchestrationResponse.Command(kind, payload),
                    new MultiAgentOrchestrationResponse.Orchestration(route, true));
        }
    }

    private static final class RecordingShadowTelemetry
            implements SupervisorShadowTelemetry {
        private SupervisorPolicyMode mode;
        private SupervisorShadowComparison comparison;

        @Override
        public void record(
                SupervisorPolicyMode mode,
                SupervisorShadowComparison comparison) {
            this.mode = mode;
            this.comparison = comparison;
        }
    }
}
