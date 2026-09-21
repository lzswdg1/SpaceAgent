package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.runtime.api.AgentRunConfigurationSnapshotApplicationApi;
import com.spaceagent.platform.runtime.api.CreateHandoffCommand;
import com.spaceagent.platform.runtime.api.GraphV2RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeContinuationHandler;
import com.spaceagent.platform.runtime.api.RuntimeToolExecutionApplicationApi;
import com.spaceagent.platform.runtime.domain.HandoffSnapshot;
import com.spaceagent.platform.runtime.domain.HandoffTestStatus;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Production executor for one Java-owned persistent graph command boundary. */
@Component
public class GraphV2CommandExecutionHandler implements RuntimeContinuationHandler {
    private final GraphV2RuntimeApplicationApi graph;
    private final RuntimeApplicationApi runtime;
    private final AgentRunConfigurationSnapshotApplicationApi runConfigurations;
    private final ModelPoolApplicationApi pools;
    private final InferenceExecutionApi inference;
    private final RuntimeToolExecutionApplicationApi tools;
    private final MultiAgentCollaborationApplicationApi collaboration;
    private final ObjectMapper json;

    public GraphV2CommandExecutionHandler(
            GraphV2RuntimeApplicationApi graph, RuntimeApplicationApi runtime,
            AgentRunConfigurationSnapshotApplicationApi runConfigurations, ModelPoolApplicationApi pools,
            InferenceExecutionApi inference, RuntimeToolExecutionApplicationApi tools,
            MultiAgentCollaborationApplicationApi collaboration, ObjectMapper json) {
        this.graph = graph;
        this.runtime = runtime;
        this.runConfigurations = runConfigurations;
        this.pools = pools;
        this.inference = inference;
        this.tools = tools;
        this.collaboration = collaboration;
        this.json = json;
    }

    @Override
    public RuntimeContinuationType type() { return RuntimeContinuationType.GRAPH_COMMAND_EXECUTION; }

    @Override
    public boolean completesRun() { return false; }

    @Override
    public void handle(ContinuationHandlerContext context) {
        Payload payload = decode(context.continuation().payload());
        var lease = context.lease();
        var executionCommand = new GraphV2RuntimeApplicationApi.BeginExecutionCommand(
                payload.tenantId(), payload.ownerUserId(), payload.graphSessionId(),
                payload.commandId(), payload.inputHash(), lease.leaseOwner(),
                lease.leaseToken(), lease.fencingToken());
        var command = graph.beginExecution(executionCommand);
        if (!command.execute()) return;
        try {
            execute(command);
            graph.completeExecution(new GraphV2RuntimeApplicationApi.CompleteExecutionCommand(
                    payload.tenantId(), payload.ownerUserId(), payload.graphSessionId(),
                    payload.commandId(), payload.inputHash(), lease.leaseOwner(),
                    lease.leaseToken(), lease.fencingToken(), payload.maxDepth(),
                    payload.maxAgents(), payload.remainingTokenBudget()));
        } catch (RuntimeException failure) {
            try {
                graph.blockExecutionUnknown(new GraphV2RuntimeApplicationApi.BlockExecutionCommand(
                        payload.tenantId(), payload.ownerUserId(), payload.graphSessionId(),
                        payload.commandId(), payload.inputHash(), lease.leaseOwner(),
                        lease.leaseToken(), lease.fencingToken()));
            } catch (RuntimeException stale) {
                failure.addSuppressed(stale);
            }
        }
    }

    private void execute(GraphV2RuntimeApplicationApi.ExecutionView command) {
        JsonNode payload = tree(command.payloadJson());
        switch (command.kind()) {
            case MODEL_REQUESTED -> executeModel(command, payload);
            case TOOL_REQUESTED -> executeTool(command, payload);
            case DELEGATE_SUBTASK -> executeDelegation(command, payload);
            case HANDOFF_PROPOSED -> executeHandoff(command, payload);
            case REVIEW_REQUIRED -> executeReview(command, payload);
            case WAIT_FOR_APPROVAL -> throw new IllegalStateException("GRAPH_APPROVAL_REQUIRED");
            case COMPLETED -> { }
        }
    }

    private void executeModel(GraphV2RuntimeApplicationApi.ExecutionView command, JsonNode payload) {
        var run = requireRun(command);
        var config = runConfigurations.require(
                command.tenantId(), command.ownerUserId(), run.id());
        if (!"SNAPSHOTTED".equals(config.state())) {
            throw new IllegalStateException("GRAPH_RUN_CONFIGURATION_UNAVAILABLE");
        }
        String runStepId = text(payload, "runStepId");
        List<InferenceExecutionApi.InferenceMessage> messages = json.convertValue(
                required(payload, "messages"), new TypeReference<>() {});
        Map<String, Object> parameters = payload.has("parameters")
                ? json.convertValue(payload.get("parameters"), new TypeReference<>() {}) : Map.of();
        var resolution = config.modelPoolId() == null ? null
                : pools.resolvePool(command.tenantId(), command.ownerUserId(),
                        config.modelPoolId(), command.commandId());
        var candidates = resolution == null ? List.<InferenceExecutionApi.InferenceCandidate>of()
                : resolution.candidates().stream().map(value ->
                        new InferenceExecutionApi.InferenceCandidate(
                                value.memberId(), value.providerId(), value.providerModelId(),
                                value.modelId(), value.priority(), value.weight(),
                                value.healthLatencyMs(), value.priceId(),
                                value.inputMicrosPerMillionTokens(),
                                value.outputMicrosPerMillionTokens())).toList();
        var result = inference.execute(new InferenceExecutionApi.InferenceExecutionCommand(
                payload.path("providerType").asText("generic"), config.modelId(), messages,
                parameters, run.id(), runStepId,
                "graph:" + command.graphSessionId() + ":" + command.commandId(),
                command.tenantId(), config.modelPoolId(), candidates,
                resolution == null ? "PRIORITY" : resolution.routingStrategy().name(),
                resolution == null ? null : resolution.candidateSnapshotHash(),
                resolution != null && resolution.fallbackEnabled()));
        for (var toolCall : result.toolCalls()) {
            var toolResult = tools.execute(new RuntimeToolExecutionApplicationApi.ExecuteRuntimeToolCommand(
                    command.ownerUserId(), run.id(), runStepId, toolCall.id(),
                    toolCall.name(), toolCall.arguments()));
            if ("UNKNOWN".equals(toolResult.status())) {
                throw new IllegalStateException("GRAPH_TOOL_OUTCOME_UNKNOWN");
            }
        }
    }

    private void executeTool(GraphV2RuntimeApplicationApi.ExecutionView command, JsonNode payload) {
        var run = requireRun(command);
        var result = tools.execute(new RuntimeToolExecutionApplicationApi.ExecuteRuntimeToolCommand(
                command.ownerUserId(), run.id(), text(payload, "runStepId"),
                text(payload, "toolCallId"), text(payload, "toolId"),
                canonical(required(payload, "arguments"))));
        if ("UNKNOWN".equals(result.status())) {
            throw new IllegalStateException("GRAPH_TOOL_OUTCOME_UNKNOWN");
        }
    }

    private void executeDelegation(GraphV2RuntimeApplicationApi.ExecutionView command, JsonNode payload) {
        var existing = collaboration.delegations(command.ownerUserId(), command.agentRunId()).stream()
                .filter(value -> value.targetAgentId().equals(text(payload, "targetAgentId")))
                .findFirst();
        if (existing.isPresent()) return;
        collaboration.delegate(new MultiAgentCollaborationApplicationApi.DelegateCommand(
                command.ownerUserId(), command.agentRunId(), text(payload, "targetAgentId"),
                text(payload, "sourceRepositoryId"), text(payload, "baseRef")));
    }

    private void executeReview(GraphV2RuntimeApplicationApi.ExecutionView command, JsonNode payload) {
        collaboration.requestReview(new MultiAgentCollaborationApplicationApi.ReviewCommand(
                command.ownerUserId(), command.agentRunId(), text(payload, "childRunId"),
                text(payload, "reviewerAgentId"), strings(payload, "artifactIds")));
    }

    private void executeHandoff(GraphV2RuntimeApplicationApi.ExecutionView command, JsonNode payload) {
        runtime.createHandoff(new CreateHandoffCommand(command.agentRunId(), new HandoffSnapshot(
                text(payload, "goal"), text(payload, "currentState"),
                strings(payload, "completedWork"), strings(payload, "decisions"),
                strings(payload, "failedAttempts"), strings(payload, "changedFiles"),
                HandoffTestStatus.valueOf(text(payload, "testStatus")),
                strings(payload, "blockers"), strings(payload, "nextActions"))));
    }

    private com.spaceagent.platform.runtime.api.AgentRunView requireRun(
            GraphV2RuntimeApplicationApi.ExecutionView command) {
        return runtime.findRun(command.agentRunId())
                .filter(value -> value.tenantId().equals(command.tenantId()))
                .filter(value -> value.ownerId().equals(command.ownerUserId()))
                .orElseThrow(() -> new IllegalStateException("GRAPH_RUN_SCOPE_MISMATCH"));
    }

    private Payload decode(String value) {
        try { return json.readValue(value, Payload.class); }
        catch (Exception exception) { throw new IllegalArgumentException("graph command payload invalid", exception); }
    }

    private JsonNode tree(String value) {
        try {
            JsonNode node = json.readTree(value);
            if (!node.isObject()) throw new IllegalArgumentException();
            return node;
        } catch (Exception exception) {
            throw new IllegalArgumentException("graph command body invalid", exception);
        }
    }

    private String canonical(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("graph arguments invalid", exception); }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private List<String> strings(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isArray() || value.size() > 256) throw new IllegalArgumentException(field + " is invalid");
        return json.convertValue(value, new TypeReference<>() {});
    }

    public record Payload(
            String tenantId, String ownerUserId, String agentRunId, String graphSessionId,
            String commandId, String inputHash, int maxDepth, int maxAgents,
            int remainingTokenBudget) {}
}
