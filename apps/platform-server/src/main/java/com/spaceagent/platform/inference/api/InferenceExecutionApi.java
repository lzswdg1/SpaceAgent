package com.spaceagent.platform.inference.api;

import java.util.List;

/**
 * Public model-independent inference execution API. Callers submit generic messages and
 * parameters; provider routing and LangChain4j execution remain inside inference
 * infrastructure adapters.
 */
public interface InferenceExecutionApi {

    InferenceExecutionResult execute(InferenceExecutionCommand command);

    default InferenceExecutionResult executeStreaming(
            InferenceExecutionCommand command,
            InferenceStreamObserver observer) {
        InferenceExecutionResult result = execute(command);
        if (!result.reasoningContent().isBlank()) {
            observer.onReasoningDelta(result.reasoningContent());
        }
        if (!result.content().isBlank()) {
            observer.onContentDelta(result.content());
        }
        return result;
    }

    interface InferenceStreamObserver {
        void onReasoningDelta(String content);
        void onContentDelta(String content);
    }

    record InferenceExecutionCommand(
            String providerType,
            String modelId,
            List<InferenceMessage> messages,
            java.util.Map<String, Object> parameters,
            String agentRunId,
            String runStepId,
            String logicalCallId,
            String tenantId,
            String modelPoolId,
            List<InferenceCandidate> candidates,
            String routingStrategy,
            String candidateSnapshotHash,
            boolean fallbackEnabled) {

        public InferenceExecutionCommand {
            providerType = providerType == null || providerType.isBlank() ? "generic" : providerType;
            modelId = modelId == null || modelId.isBlank() ? "default" : modelId;
            messages = messages == null ? List.of() : List.copyOf(messages);
            parameters = parameters == null ? java.util.Map.of() : java.util.Map.copyOf(parameters);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            boolean hasLedgerIdentity = agentRunId != null || runStepId != null || logicalCallId != null;
            if (hasLedgerIdentity
                    && (isBlank(agentRunId) || isBlank(runStepId) || isBlank(logicalCallId))) {
                throw new IllegalArgumentException(
                        "agentRunId, runStepId and logicalCallId must be provided together");
            }
        }

        public InferenceExecutionCommand(String providerType,String modelId,List<InferenceMessage> messages,java.util.Map<String,Object> parameters,String agentRunId,String runStepId,String logicalCallId){this(providerType,modelId,messages,parameters,agentRunId,runStepId,logicalCallId,null,null,List.of(),"PRIORITY",null,false);}

        public InferenceExecutionCommand(
                String providerType,
                String modelId,
                List<InferenceMessage> messages,
                java.util.Map<String, Object> parameters) {
            this(providerType, modelId, messages, parameters, null, null, null,
                    null,null,List.of(),"PRIORITY",null,false);
        }

        public boolean durable() {
            return agentRunId != null;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    record InferenceCandidate(String memberId,String providerId,String providerModelId,
            String modelId,int priority,int weight,Integer healthLatencyMs,String priceId,
            Long inputMicrosPerMillionTokens,Long outputMicrosPerMillionTokens){
        public InferenceCandidate{if(providerId==null||providerId.isBlank()||modelId==null||modelId.isBlank())throw new IllegalArgumentException("candidate provider/model required");}
    }

    record InferenceMessage(String role, String content) {
        public InferenceMessage {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("role must not be blank");
            }
            if (content == null) {
                content = "";
            }
        }
    }

    record InferenceExecutionResult(
            String content,
            int inputTokens,
            int outputTokens,
            List<InferenceToolCall> toolCalls,
            String finishReason,
            String providerRequestId,
            java.util.Map<String, Object> usage,
            String selectedProviderId,
            String selectedModelId,
            int attemptCount,
            String candidateSnapshotHash,
            String reasoningContent) {

        public InferenceExecutionResult {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            finishReason = finishReason == null ? "" : finishReason;
            usage = usage == null ? java.util.Map.of() : java.util.Map.copyOf(usage);
            reasoningContent = reasoningContent == null ? "" : reasoningContent;
        }

        public InferenceExecutionResult(
                String content,
                int inputTokens,
                int outputTokens,
                List<InferenceToolCall> toolCalls,
                String finishReason,
                String providerRequestId,
                java.util.Map<String, Object> usage,
                String selectedProviderId,
                String selectedModelId,
                int attemptCount,
                String candidateSnapshotHash) {
            this(content, inputTokens, outputTokens, toolCalls, finishReason,
                    providerRequestId, usage, selectedProviderId, selectedModelId,
                    attemptCount, candidateSnapshotHash, "");
        }

        public InferenceExecutionResult(String content,int inputTokens,int outputTokens,List<InferenceToolCall> toolCalls,String finishReason,String providerRequestId,java.util.Map<String,Object> usage){this(content,inputTokens,outputTokens,toolCalls,finishReason,providerRequestId,usage,null,null,1,null,"");}

        public InferenceExecutionResult(String content, int inputTokens, int outputTokens) {
            this(content, inputTokens, outputTokens, List.of(), "", null, java.util.Map.of(),null,null,1,null,"");
        }

        public InferenceExecutionResult(
                String content,
                int inputTokens,
                int outputTokens,
                List<InferenceToolCall> toolCalls) {
            this(content, inputTokens, outputTokens, toolCalls, "", null, java.util.Map.of(),null,null,1,null,"");
        }
    }

    record InferenceToolCall(String id, String name, String arguments) {

        public InferenceToolCall {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("tool call id must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool call name must not be blank");
            }
            arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
        }
    }
}
