package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.runtime.api.AgentRunConfigurationSnapshotApplicationApi;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.GraphV2OrchestrationApplicationApi;
import com.spaceagent.platform.runtime.api.GraphV2RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeContinuationHandler;
import com.spaceagent.platform.runtime.api.RuntimeToolExecutionApplicationApi;
import com.spaceagent.platform.runtime.application.GraphV2CommandExecutionHandler;
import com.spaceagent.platform.runtime.application.GraphV2CommandHasher;
import com.spaceagent.platform.runtime.application.GraphV2ContinuationHandler;
import com.spaceagent.platform.runtime.application.GraphV2RuntimeApplicationService;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.GraphSessionState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.platform.runtime.domain.RuntimeGraphCommand;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryRuntimeGraphSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphV2ContinuationApplicationTest {
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final String BUNDLE = "sha256:" + "a".repeat(64);

    @Test
    void productionHandlerExecutesToolThroughLedgerBoundaryThenContinuesGraph() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var repository = new InMemoryRuntimeGraphSessionRepository();
        var coordination = mock(RuntimeCoordinationApplicationApi.class);
        var graph = new GraphV2RuntimeApplicationService(
                repository, () -> NOW, () -> "session", coordination, json);
        graph.start(new GraphV2RuntimeApplicationApi.StartCommand("tenant", "owner", "run", BUNDLE));
        Map<String, Object> payload = Map.of(
                "runStepId", "step", "toolCallId", "tool-call", "toolId", "echo",
                "arguments", Map.of("value", "ok"));
        String inputHash = GraphV2CommandHasher.hash(
                json, "session", 0, GraphV2Contract.Kind.TOOL_REQUESTED, payload);
        graph.accept(new GraphV2RuntimeApplicationApi.AcceptCommand(
                "tenant", "owner", "session",
                new GraphV2Contract.Result(GraphV2Contract.VERSION, "request", "session", BUNDLE,
                        new GraphV2Contract.Cursor(1, List.of(), "cmd"),
                        new GraphV2Contract.Command("cmd", GraphV2Contract.Kind.TOOL_REQUESTED,
                                inputHash, payload), true), 2, 2, 100));

        ArgumentCaptor<RuntimeCoordinationApplicationApi.EnqueueContinuationCommand> enqueued =
                ArgumentCaptor.forClass(RuntimeCoordinationApplicationApi.EnqueueContinuationCommand.class);
        verify(coordination).enqueue(enqueued.capture());
        assertThat(enqueued.getValue().type()).isEqualTo(RuntimeContinuationType.GRAPH_COMMAND_EXECUTION);
        var runtime = mock(RuntimeApplicationApi.class);
        AgentRunView run = mock(AgentRunView.class);
        when(run.id()).thenReturn("run");
        when(run.tenantId()).thenReturn("tenant");
        when(run.ownerId()).thenReturn("owner");
        when(run.state()).thenReturn(AgentRunState.IN_PROGRESS);
        when(runtime.findRun("run")).thenReturn(Optional.of(run));
        var tools = mock(RuntimeToolExecutionApplicationApi.class);
        when(tools.execute(any())).thenReturn(new RuntimeToolExecutionApplicationApi.RuntimeToolResult(
                "tool-call", "echo", "SUCCEEDED", "ok", null, null));
        var handler = new GraphV2CommandExecutionHandler(graph, runtime,
                mock(AgentRunConfigurationSnapshotApplicationApi.class), mock(ModelPoolApplicationApi.class),
                mock(InferenceExecutionApi.class), tools,
                mock(MultiAgentCollaborationApplicationApi.class), json);
        var continuation = mock(RuntimeCoordinationApplicationApi.ContinuationView.class);
        when(continuation.agentRunId()).thenReturn("run");
        when(continuation.payload()).thenReturn(enqueued.getValue().payload());
        var lease = mock(RuntimeCoordinationApplicationApi.LeaseView.class);
        when(lease.leaseOwner()).thenReturn("worker");
        when(lease.leaseToken()).thenReturn("00000000-0000-0000-0000-000000000001");
        when(lease.fencingToken()).thenReturn(1L);

        handler.handle(new RuntimeContinuationHandler.ContinuationHandlerContext(continuation, lease));

        verify(tools).execute(argThat(command -> command.agentRunId().equals("run")
                && command.toolCallId().equals("tool-call") && command.toolId().equals("echo")));
        assertThat(repository.findCommand("session", "cmd").orElseThrow().state())
                .isEqualTo(RuntimeGraphCommand.State.CONFIRMED);
        assertThat(graph.start(new GraphV2RuntimeApplicationApi.StartCommand(
                "tenant", "owner", "run", BUNDLE)).state()).isEqualTo(GraphSessionState.ACTIVE);
        verify(coordination, org.mockito.Mockito.times(2)).enqueue(enqueued.capture());
        assertThat(enqueued.getAllValues().getLast().type()).isEqualTo(RuntimeContinuationType.GRAPH_COMMAND);
    }

    @Test
    void restartedExecutingCommandBecomesUnknownWithoutRepeatingTool() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var repository = new InMemoryRuntimeGraphSessionRepository();
        var graph = new GraphV2RuntimeApplicationService(repository, () -> NOW, () -> "session");
        graph.start(new GraphV2RuntimeApplicationApi.StartCommand("tenant", "owner", "run", BUNDLE));
        Map<String, Object> payload = Map.of(
                "runStepId", "step", "toolCallId", "tool-call", "toolId", "echo",
                "arguments", Map.of());
        String inputHash = GraphV2CommandHasher.hash(
                json, "session", 0, GraphV2Contract.Kind.TOOL_REQUESTED, payload);
        graph.accept(new GraphV2RuntimeApplicationApi.AcceptCommand(
                "tenant", "owner", "session",
                new GraphV2Contract.Result(GraphV2Contract.VERSION, "request", "session", BUNDLE,
                        new GraphV2Contract.Cursor(1, List.of(), "cmd"),
                        new GraphV2Contract.Command("cmd", GraphV2Contract.Kind.TOOL_REQUESTED,
                                inputHash, payload), true)));
        graph.beginExecution(new GraphV2RuntimeApplicationApi.BeginExecutionCommand(
                "tenant", "owner", "session", "cmd", inputHash,
                "old-worker", "00000000-0000-0000-0000-000000000001", 1));
        var tools = mock(RuntimeToolExecutionApplicationApi.class);
        var handler = new GraphV2CommandExecutionHandler(graph, mock(RuntimeApplicationApi.class),
                mock(AgentRunConfigurationSnapshotApplicationApi.class), mock(ModelPoolApplicationApi.class),
                mock(InferenceExecutionApi.class), tools,
                mock(MultiAgentCollaborationApplicationApi.class), json);
        var continuation = mock(RuntimeCoordinationApplicationApi.ContinuationView.class);
        when(continuation.agentRunId()).thenReturn("run");
        when(continuation.payload()).thenReturn(json.writeValueAsString(
                new GraphV2CommandExecutionHandler.Payload(
                        "tenant", "owner", "run", "session", "cmd", inputHash, 2, 2, 100)));
        var lease = mock(RuntimeCoordinationApplicationApi.LeaseView.class);
        when(lease.leaseOwner()).thenReturn("new-worker");
        when(lease.leaseToken()).thenReturn("00000000-0000-0000-0000-000000000002");
        when(lease.fencingToken()).thenReturn(2L);

        handler.handle(new RuntimeContinuationHandler.ContinuationHandlerContext(continuation, lease));

        assertThat(repository.findCommand("session", "cmd").orElseThrow().state())
                .isEqualTo(RuntimeGraphCommand.State.UNKNOWN);
        verify(tools, never()).execute(any());
    }

    @Test
    void productionHandlerRoutesModelDelegationAndHandoffThroughExistingPublicApis() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var graph = mock(GraphV2RuntimeApplicationApi.class);
        var runtime = mock(RuntimeApplicationApi.class);
        var runConfigurations = mock(AgentRunConfigurationSnapshotApplicationApi.class);
        var pools = mock(ModelPoolApplicationApi.class);
        var inference = mock(InferenceExecutionApi.class);
        var tools = mock(RuntimeToolExecutionApplicationApi.class);
        var collaboration = mock(MultiAgentCollaborationApplicationApi.class);
        AgentRunView run = mock(AgentRunView.class);
        when(run.id()).thenReturn("run");
        when(run.tenantId()).thenReturn("tenant");
        when(run.ownerId()).thenReturn("owner");
        when(run.configurationSnapshotId()).thenReturn("run");
        when(runtime.findRun("run")).thenReturn(Optional.of(run));
        var configuration = mock(AgentRunConfigurationSnapshotApplicationApi.SnapshotView.class);
        when(configuration.state()).thenReturn("SNAPSHOTTED");
        when(configuration.modelId()).thenReturn("model");
        when(runConfigurations.require("tenant", "owner", "run")).thenReturn(configuration);
        when(inference.execute(any())).thenReturn(new InferenceExecutionApi.InferenceExecutionResult(
                "ok", 1, 1, List.of()));
        when(collaboration.delegations("owner", "run")).thenReturn(List.of());
        when(graph.beginExecution(any())).thenReturn(
                execution(GraphV2Contract.Kind.MODEL_REQUESTED,
                        "{\"runStepId\":\"step\",\"messages\":[{\"role\":\"user\",\"content\":\"go\"}]}"),
                execution(GraphV2Contract.Kind.DELEGATE_SUBTASK,
                        "{\"targetAgentId\":\"agent-2\",\"sourceRepositoryId\":\"source\",\"baseRef\":\"main\"}"),
                execution(GraphV2Contract.Kind.HANDOFF_PROPOSED,
                        "{\"goal\":\"continue\",\"currentState\":\"ready\",\"completedWork\":[],\"decisions\":[],\"failedAttempts\":[],\"changedFiles\":[],\"testStatus\":\"NOT_RUN\",\"blockers\":[],\"nextActions\":[\"continue\"]}"));
        var handler = new GraphV2CommandExecutionHandler(
                graph, runtime, runConfigurations, pools, inference, tools, collaboration, json);
        for (String commandId : List.of("model", "delegate", "handoff")) {
            handler.handle(context(json, commandId));
        }

        verify(inference).execute(argThat(command -> command.agentRunId().equals("run")
                && command.logicalCallId().startsWith("graph:")));
        verify(collaboration).delegate(argThat(command -> command.parentRunId().equals("run")
                && command.targetAgentId().equals("agent-2")));
        verify(runtime).createHandoff(argThat(command -> command.sourceAgentRunId().equals("run")
                && command.snapshot().goal().equals("continue")));
        verify(graph, org.mockito.Mockito.times(3)).completeExecution(any());
    }

    @Test
    void graphContinuationResumesExactRunAndLeavesRunLifecycleOpen() throws Exception {
        var graph = mock(GraphV2OrchestrationApplicationApi.class);
        var json = new ObjectMapper();
        var handler = new GraphV2ContinuationHandler(graph, json);
        var continuation = mock(RuntimeCoordinationApplicationApi.ContinuationView.class);
        when(continuation.agentRunId()).thenReturn("run");
        when(continuation.payload()).thenReturn(json.writeValueAsString(
                new GraphV2ContinuationHandler.Payload("tenant", "owner", "run", BUNDLE, 2, 2, 100)));
        handler.handle(new RuntimeContinuationHandler.ContinuationHandlerContext(
                continuation, mock(RuntimeCoordinationApplicationApi.LeaseView.class)));
        verify(graph).transition(argThat(command -> command.agentRunId().equals("run")
                && command.bundleHash().equals(BUNDLE)));
        assertThat(handler.completesRun()).isFalse();
    }

    private static GraphV2RuntimeApplicationApi.ExecutionView execution(
            GraphV2Contract.Kind kind, String payload) {
        return new GraphV2RuntimeApplicationApi.ExecutionView(
                "session", "run", "tenant", "owner", "command", "sha256:" + "b".repeat(64),
                kind, 1, payload, true);
    }

    private static RuntimeContinuationHandler.ContinuationHandlerContext context(
            ObjectMapper json, String commandId) throws Exception {
        var continuation = mock(RuntimeCoordinationApplicationApi.ContinuationView.class);
        when(continuation.agentRunId()).thenReturn("run");
        when(continuation.payload()).thenReturn(json.writeValueAsString(
                new GraphV2CommandExecutionHandler.Payload(
                        "tenant", "owner", "run", "session", commandId,
                        "sha256:" + "b".repeat(64), 2, 2, 100)));
        var lease = mock(RuntimeCoordinationApplicationApi.LeaseView.class);
        when(lease.leaseOwner()).thenReturn("worker");
        when(lease.leaseToken()).thenReturn("00000000-0000-0000-0000-000000000001");
        when(lease.fencingToken()).thenReturn(1L);
        return new RuntimeContinuationHandler.ContinuationHandlerContext(continuation, lease);
    }
}
