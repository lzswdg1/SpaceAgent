package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.inference.api.ModelPoolResolutionView;
import com.spaceagent.platform.inference.api.ResolvedModelCandidateView;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.CompleteRunStepCommand;
import com.spaceagent.platform.runtime.api.FailRunStepCommand;
import com.spaceagent.platform.runtime.api.RunStepView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.StartRunStepCommand;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes one Supervisor reasoning boundary through Java-owned Inference authority. */
@Service
public class ProviderBackedSupervisorReasoningService {

    private final RuntimeApplicationApi runtimeApi;
    private final ModelPoolApplicationApi modelPoolApi;
    private final InferenceExecutionApi inferenceApi;

    public ProviderBackedSupervisorReasoningService(
            RuntimeApplicationApi runtimeApi,
            ModelPoolApplicationApi modelPoolApi,
            InferenceExecutionApi inferenceApi) {
        this.runtimeApi = runtimeApi;
        this.modelPoolApi = modelPoolApi;
        this.inferenceApi = inferenceApi;
    }

    MultiAgentOrchestrationRequest.ReasoningResult execute(
            AgentRunView run,
            MultiAgentOrchestrationResponse.Command command,
            int remainingTokenBudget) {
        String modelPoolRef = (String) command.payload().get("modelPoolRef");
        String logicalCallId = (String) command.payload().get("logicalCallId");
        ModelPoolResolutionView resolution = modelPoolApi.resolvePool(
                run.tenantId(), run.ownerId(), modelPoolRef, logicalCallId);
        ResolvedModelCandidateView primary = resolution.candidates().getFirst();
        RunStepView step = runtimeApi.startStep(new StartRunStepCommand(
                run.id(), "multi-agent-supervisor-reasoning"));
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawMessages =
                    (List<Map<String, Object>>) command.payload().get("messages");
            List<InferenceExecutionApi.InferenceMessage> messages = rawMessages.stream()
                    .map(message -> new InferenceExecutionApi.InferenceMessage(
                            (String) message.get("role"), (String) message.get("content")))
                    .toList();
            @SuppressWarnings("unchecked")
            Map<String, Object> rawParameters =
                    (Map<String, Object>) command.payload().get("parameters");
            Map<String, Object> parameters = new LinkedHashMap<>(rawParameters);
            int requestedTokens = ((Number) parameters.get("maxOutputTokens")).intValue();
            parameters.put("maxOutputTokens", Math.min(requestedTokens, remainingTokenBudget));
            InferenceExecutionApi.InferenceExecutionResult inference = inferenceApi.execute(
                    new InferenceExecutionApi.InferenceExecutionCommand(
                            primary.providerId(), primary.modelId(), messages, parameters,
                            run.id(), step.id(), logicalCallId, run.tenantId(), modelPoolRef,
                            resolution.candidates().stream()
                                    .map(ProviderBackedSupervisorReasoningService::toCandidate)
                                    .toList(),
                            resolution.routingStrategy().name(),
                            resolution.candidateSnapshotHash(),
                            resolution.fallbackEnabled()));
            MultiAgentOrchestrationRequest.ReasoningResult result =
                    new MultiAgentOrchestrationRequest.ReasoningResult(
                            inference.content(), inference.inputTokens(), inference.outputTokens(),
                            inference.selectedProviderId(), inference.selectedModelId(),
                            inference.candidateSnapshotHash());
            runtimeApi.completeStep(new CompleteRunStepCommand(run.id(), step.id()));
            return result;
        } catch (RuntimeException error) {
            try {
                runtimeApi.failStep(new FailRunStepCommand(run.id(), step.id()));
            } catch (RuntimeException ignored) {
                // Preserve the authoritative inference error; the RunStep remains inspectable.
            }
            throw error;
        }
    }

    private static InferenceExecutionApi.InferenceCandidate toCandidate(
            ResolvedModelCandidateView candidate) {
        return new InferenceExecutionApi.InferenceCandidate(
                candidate.memberId(), candidate.providerId(), candidate.providerModelId(),
                candidate.modelId(), candidate.priority(), candidate.weight(),
                candidate.healthLatencyMs(), candidate.priceId(),
                candidate.inputMicrosPerMillionTokens(),
                candidate.outputMicrosPerMillionTokens());
    }
}
