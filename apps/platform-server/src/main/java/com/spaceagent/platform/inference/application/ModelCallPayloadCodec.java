package com.spaceagent.platform.inference.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ModelCallPayloadCodec {

    private final ObjectMapper objectMapper;

    public ModelCallPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EncodedPayload encode(InferenceExecutor.InferenceExecution result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", result.content());
        response.put("finishReason", result.finishReason());
        response.put("toolCalls", result.toolCalls().stream()
                .map(call -> Map.of(
                        "id", call.id(),
                        "name", call.name(),
                        "arguments", call.arguments()))
                .toList());

        Map<String, Object> usage = new LinkedHashMap<>(result.usage());
        usage.put("inputTokens", result.inputTokens());
        usage.put("outputTokens", result.outputTokens());
        usage.put("totalTokens", result.inputTokens() + result.outputTokens());
        try {
            return new EncodedPayload(
                    objectMapper.writeValueAsString(response),
                    objectMapper.writeValueAsString(usage));
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new ModelCallPayloadException("unable to encode standardized inference response", exception);
        }
    }

    public InferenceExecutionApi.InferenceExecutionResult decode(
            String responsePayload,
            String usagePayload,
            String providerRequestId) {
        try {
            JsonNode response = objectMapper.readTree(responsePayload == null ? "{}" : responsePayload);
            JsonNode usageNode = objectMapper.readTree(usagePayload == null ? "{}" : usagePayload);
            List<InferenceExecutionApi.InferenceToolCall> toolCalls = new ArrayList<>();
            for (JsonNode call : response.path("toolCalls")) {
                toolCalls.add(new InferenceExecutionApi.InferenceToolCall(
                        call.path("id").asText(),
                        call.path("name").asText(),
                        call.path("arguments").asText("{}")));
            }
            Map<String, Object> usage = objectMapper.convertValue(
                    usageNode,
                    new TypeReference<Map<String, Object>>() { });
            return new InferenceExecutionApi.InferenceExecutionResult(
                    response.path("content").asText(""),
                    usageNode.path("inputTokens").asInt(0),
                    usageNode.path("outputTokens").asInt(0),
                    toolCalls,
                    response.path("finishReason").asText(""),
                    providerRequestId,
                    usage);
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new ModelCallPayloadException("unable to decode persisted inference response", exception);
        }
    }

    public record EncodedPayload(String responsePayload, String usagePayload) {
    }

    public static class ModelCallPayloadException extends RuntimeException {
        public ModelCallPayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
