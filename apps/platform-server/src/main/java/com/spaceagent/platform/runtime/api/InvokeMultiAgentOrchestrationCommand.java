package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;

import java.util.List;
import java.util.Objects;

/** Runtime-owned inputs that are safe to send to the TypeScript compute service. */
public record InvokeMultiAgentOrchestrationCommand(
        String agentRunId,
        List<MultiAgentOrchestrationRequest.AgentRef> agentRefs,
        String modelPoolRef,
        String sourceRepositoryId,
        String workspaceBaseRef,
        MultiAgentOrchestrationRequest.Context context,
        MultiAgentOrchestrationRequest.Capabilities capabilities,
        MultiAgentOrchestrationRequest.Limits limits) {

    public InvokeMultiAgentOrchestrationCommand {
        if (agentRunId == null || agentRunId.isBlank()) {
            throw new IllegalArgumentException("agentRunId is required");
        }
        agentRefs = agentRefs == null ? List.of() : List.copyOf(agentRefs);
        context = Objects.requireNonNull(context, "context");
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        limits = Objects.requireNonNull(limits, "limits");
    }

    public InvokeMultiAgentOrchestrationCommand(
            String agentRunId,
            List<MultiAgentOrchestrationRequest.AgentRef> agentRefs,
            String modelPoolRef,
            MultiAgentOrchestrationRequest.Context context,
            MultiAgentOrchestrationRequest.Capabilities capabilities,
            MultiAgentOrchestrationRequest.Limits limits) {
        this(agentRunId, agentRefs, modelPoolRef, null, null, context, capabilities, limits);
    }
}
