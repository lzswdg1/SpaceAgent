package com.spaceagent.platform.inference.domain;

import java.util.List;
import java.util.Map;

/**
 * Provider execution port. Provider SDKs and LangChain4j belong behind infrastructure
 * adapters implementing this port; they never appear in domain or public API contracts.
 */
public interface InferenceExecutor {

    InferenceExecution execute(InferenceExecutionRequest request);

    default InferenceExecution executeStreaming(
            InferenceExecutionRequest request,
            StreamObserver observer) {
        InferenceExecution result = execute(request);
        if (!result.reasoningContent().isBlank()) {
            observer.onReasoningDelta(result.reasoningContent());
        }
        if (!result.content().isBlank()) {
            observer.onContentDelta(result.content());
        }
        return result;
    }

    interface StreamObserver {
        default void onFirstChunk() { }
        void onReasoningDelta(String content);
        void onContentDelta(String content);
    }

    record InferenceExecutionRequest(
            String providerType,
            String modelId,
            List<InferenceMessage> messages,
            Map<String, Object> parameters,
            List<InferenceToolDefinition> tools) {

        public InferenceExecutionRequest {
            providerType = providerType == null || providerType.isBlank() ? "generic" : providerType;
            modelId = modelId == null || modelId.isBlank() ? "default" : modelId;
            messages = messages == null ? List.of() : List.copyOf(messages);
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public InferenceExecutionRequest(
                String providerType,
                String modelId,
                List<InferenceMessage> messages,
                Map<String, Object> parameters) {
            this(providerType, modelId, messages, parameters, List.of());
        }
    }

    record InferenceExecution(
            String content,
            int inputTokens,
            int outputTokens,
            List<InferenceToolCall> toolCalls,
            String finishReason,
            String providerRequestId,
            Map<String, Object> usage,
            String reasoningContent) {

        public InferenceExecution {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            finishReason = finishReason == null ? "" : finishReason;
            usage = usage == null ? Map.of() : Map.copyOf(usage);
            reasoningContent = reasoningContent == null ? "" : reasoningContent;
        }

        public InferenceExecution(
                String content,
                int inputTokens,
                int outputTokens,
                List<InferenceToolCall> toolCalls,
                String finishReason,
                String providerRequestId,
                Map<String, Object> usage) {
            this(content, inputTokens, outputTokens, toolCalls, finishReason,
                    providerRequestId, usage, "");
        }

        public InferenceExecution(String content, int inputTokens, int outputTokens) {
            this(content, inputTokens, outputTokens, List.of(), "", null, Map.of(), "");
        }

        public InferenceExecution(
                String content,
                int inputTokens,
                int outputTokens,
                List<InferenceToolCall> toolCalls) {
            this(content, inputTokens, outputTokens, toolCalls, "", null, Map.of(), "");
        }
    }

    record InferenceToolCall(String id, String name, String arguments) {
    }

    record InferenceToolDefinition(
            String name,
            String description,
            Map<String, Object> parameters) {

        public InferenceToolDefinition {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }
}
