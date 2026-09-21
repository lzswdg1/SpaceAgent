package com.spaceagent.platform.inference.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import com.spaceagent.platform.inference.domain.InferenceMessage;
import com.spaceagent.platform.inference.domain.InferenceProviderException;
import com.spaceagent.platform.inference.domain.InferenceProviderRepository;
import com.spaceagent.platform.inference.domain.ModelCallStatus;
import com.spaceagent.platform.inference.domain.ModelProvider;
import com.spaceagent.platform.inference.domain.ModelProviderSecretCipher;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real OpenAI-compatible provider adapter for the platform inference boundary.
 * Provider secrets remain encrypted at rest and are resolved only inside inference
 * infrastructure.
 */
@Component
@ConditionalOnProperty(
        prefix = "platform.inference",
        name = "execution-mode",
        havingValue = "http",
        matchIfMissing = true)
public class OpenAiCompatibleInferenceExecutor implements InferenceExecutor {

    private final InferenceProviderRepository repository;
    private final ModelProviderSecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final InferenceProperties properties;

    public OpenAiCompatibleInferenceExecutor(
            InferenceProviderRepository repository,
            ModelProviderSecretCipher secretCipher,
            ObjectMapper objectMapper,
            InferenceProperties properties) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public InferenceExecution execute(InferenceExecutionRequest request) {
        ModelProvider provider = repository.findProviderById(request.providerType())
                .orElseThrow(() -> new BusinessException(
                        "Model provider not found",
                        HttpStatus.BAD_REQUEST,
                        "MODEL_PROVIDER_NOT_FOUND"));
        if (!provider.enabled()) {
            throw new BusinessException("Model provider is disabled", HttpStatus.BAD_REQUEST);
        }
        if (repository.findModelsByProviderId(provider.id()).stream()
                .noneMatch(model -> request.modelId().equals(model.modelId()))) {
            throw new BusinessException("Model is not configured for provider", HttpStatus.BAD_REQUEST);
        }

        if (Thread.currentThread().isInterrupted()) {
            throw providerFailure(
                    ModelCallStatus.CANCELLED,
                    "INFERENCE_CANCELLED",
                    "Inference request was cancelled before provider dispatch",
                    null);
        }
        Map<String, Object> body = requestBody(request);
        validateSerializable(body);
        RestClient client = createClient(provider);
        try {
            String response = client.post()
                    .uri("chat/completions")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(response);
        } catch (InferenceProviderException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw providerFailure(
                        ModelCallStatus.FAILED,
                        "INFERENCE_PROVIDER_REJECTED",
                        safeProviderRejectionSummary(
                                objectMapper,
                                exception.getStatusCode().value(),
                                exception.getResponseBodyAsString()),
                        exception);
            }
            throw providerFailure(
                    ModelCallStatus.UNKNOWN,
                    "INFERENCE_PROVIDER_RESPONSE_AMBIGUOUS",
                    "Inference provider returned an ambiguous HTTP failure after dispatch",
                    exception);
        } catch (RestClientException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw providerFailure(
                        ModelCallStatus.CANCELLED,
                        "INFERENCE_CANCELLED",
                        "Inference request was cancelled during provider execution",
                        exception);
            }
            if (isTimeoutFailure(exception)) {
                throw providerFailure(
                        ModelCallStatus.UNKNOWN,
                        "INFERENCE_TIMEOUT_AMBIGUOUS",
                        "Inference provider timed out after "
                                + properties.getRequestTimeoutSeconds()
                                + " seconds after dispatch; completion is unknown",
                        exception);
            }
            throw providerFailure(
                    ModelCallStatus.UNKNOWN,
                    "INFERENCE_TRANSPORT_AMBIGUOUS",
                    "Inference provider transport failed after dispatch; completion is unknown",
                    exception);
        }
    }

    @Override
    public InferenceExecution executeStreaming(
            InferenceExecutionRequest request,
            StreamObserver observer) {
        ModelProvider provider = requireProvider(request);
        if (Thread.currentThread().isInterrupted()) {
            throw providerFailure(ModelCallStatus.CANCELLED, "INFERENCE_CANCELLED",
                    "Inference request was cancelled before provider dispatch", null);
        }
        Map<String, Object> body = new LinkedHashMap<>(requestBody(request));
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        validateSerializable(body);
        RestClient client = createClient(provider);
        try {
            return client.post().uri("chat/completions").body(body).exchange((ignored, response) -> {
                int status = response.getStatusCode().value();
                if (status >= 400 && status < 500) {
                    String errorBody = new String(
                            response.getBody().readNBytes(64_001), StandardCharsets.UTF_8);
                    throw providerFailure(ModelCallStatus.FAILED,
                            "INFERENCE_PROVIDER_REJECTED",
                            safeProviderRejectionSummary(objectMapper, status, errorBody), null);
                }
                if (status < 200 || status >= 300) {
                    throw providerFailure(ModelCallStatus.UNKNOWN,
                            "INFERENCE_PROVIDER_RESPONSE_AMBIGUOUS",
                            "Inference provider returned an ambiguous HTTP failure after dispatch",
                            null);
                }
                return parseStream(response.getBody(), observer);
            });
        } catch (InferenceProviderException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw transportFailure(exception);
        } catch (Exception exception) {
            throw providerFailure(ModelCallStatus.UNKNOWN,
                    "INFERENCE_RESPONSE_AMBIGUOUS",
                    "Inference provider stream was incomplete or invalid; completion is unknown",
                    exception);
        }
    }

    private ModelProvider requireProvider(InferenceExecutionRequest request) {
        ModelProvider provider = repository.findProviderById(request.providerType())
                .orElseThrow(() -> new BusinessException(
                        "Model provider not found", HttpStatus.BAD_REQUEST,
                        "MODEL_PROVIDER_NOT_FOUND"));
        if (!provider.enabled()) {
            throw new BusinessException("Model provider is disabled", HttpStatus.BAD_REQUEST);
        }
        if (repository.findModelsByProviderId(provider.id()).stream()
                .noneMatch(model -> request.modelId().equals(model.modelId()))) {
            throw new BusinessException("Model is not configured for provider", HttpStatus.BAD_REQUEST);
        }
        return provider;
    }

    InferenceExecution parseStream(InputStream input, StreamObserver observer) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolAccumulator> tools = new LinkedHashMap<>();
        int inputTokens = 0;
        int outputTokens = 0;
        String finishReason = "";
        String providerRequestId = null;
        Map<String, Object> usage = Map.of();
        boolean firstChunkObserved = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw providerFailure(ModelCallStatus.CANCELLED, "INFERENCE_CANCELLED",
                            "Inference request was cancelled during provider execution", null);
                }
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JsonNode root = objectMapper.readTree(data);
                if (root.path("id").isTextual()) providerRequestId = root.path("id").asText();
                JsonNode usageNode = root.path("usage");
                if (usageNode.isObject() && !usageNode.isEmpty()) {
                    inputTokens = usageNode.path("prompt_tokens").asInt(inputTokens);
                    outputTokens = usageNode.path("completion_tokens").asInt(outputTokens);
                    usage = objectMapper.convertValue(
                            usageNode, new TypeReference<Map<String, Object>>() { });
                }
                if (!root.path("choices").isArray() || root.path("choices").isEmpty()) continue;
                JsonNode choice = root.path("choices").get(0);
                if (choice.path("finish_reason").isTextual()) {
                    finishReason = choice.path("finish_reason").asText("");
                }
                JsonNode delta = choice.path("delta");
                String reasoningDelta = textDelta(delta, "reasoning_content");
                if (reasoningDelta.isBlank()) reasoningDelta = textDelta(delta, "reasoning");
                String contentDelta = textDelta(delta, "content");
                boolean usefulChunk = !reasoningDelta.isEmpty() || !contentDelta.isEmpty()
                        || delta.path("tool_calls").isArray()
                        && !delta.path("tool_calls").isEmpty();
                if (usefulChunk && !firstChunkObserved) {
                    observer.onFirstChunk();
                    firstChunkObserved = true;
                }
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    observer.onReasoningDelta(reasoningDelta);
                }
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                    observer.onContentDelta(contentDelta);
                }
                for (JsonNode tool : delta.path("tool_calls")) {
                    int index = tool.path("index").isInt()
                            ? tool.path("index").asInt()
                            : tools.keySet().stream().reduce((left, right) -> right).orElse(0);
                    ToolAccumulator accumulator = tools.computeIfAbsent(
                            index, ToolAccumulator::new);
                    if (tool.path("id").isTextual()) accumulator.id = tool.path("id").asText();
                    JsonNode function = tool.path("function");
                    if (function.path("name").isTextual()) {
                        accumulator.name = function.path("name").asText();
                    }
                    if (function.path("arguments").isTextual()) {
                        accumulator.arguments.append(function.path("arguments").asText());
                    } else if (function.path("arguments").isObject()
                            && accumulator.arguments.isEmpty()) {
                        accumulator.arguments.append(
                                objectMapper.writeValueAsString(function.path("arguments")));
                    }
                }
            }
        } catch (InferenceProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw providerFailure(ModelCallStatus.UNKNOWN, "INFERENCE_RESPONSE_AMBIGUOUS",
                    "Inference provider stream was incomplete or invalid; completion is unknown",
                    exception);
        }
        List<InferenceToolCall> calls = tools.values().stream().map(ToolAccumulator::toCall).toList();
        if (content.isEmpty() && reasoning.isEmpty() && calls.isEmpty()) {
            throw providerFailure(ModelCallStatus.UNKNOWN, "INFERENCE_RESPONSE_AMBIGUOUS",
                    "Inference provider stream contained no usable completion", null);
        }
        return new InferenceExecution(content.toString(), inputTokens, outputTokens, calls,
                finishReason, providerRequestId, usage, reasoning.toString());
    }

    private static String textDelta(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    private InferenceProviderException transportFailure(RestClientException exception) {
        if (Thread.currentThread().isInterrupted()) {
            return providerFailure(ModelCallStatus.CANCELLED, "INFERENCE_CANCELLED",
                    "Inference request was cancelled during provider execution", exception);
        }
        if (isTimeoutFailure(exception)) {
            return providerFailure(ModelCallStatus.UNKNOWN, "INFERENCE_TIMEOUT_AMBIGUOUS",
                    "Inference provider timed out after " + properties.getRequestTimeoutSeconds()
                            + " seconds after dispatch; completion is unknown", exception);
        }
        return providerFailure(ModelCallStatus.UNKNOWN, "INFERENCE_TRANSPORT_AMBIGUOUS",
                "Inference provider transport failed after dispatch; completion is unknown",
                exception);
    }

    private static final class ToolAccumulator {
        private final int index;
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        private ToolAccumulator(int index) {
            this.index = index;
        }

        private InferenceToolCall toCall() {
            if (name.isBlank()) {
                throw new IllegalArgumentException("invalid streamed provider tool call");
            }
            return new InferenceToolCall(
                    id.isBlank() ? "stream-call-" + index : id,
                    name,
                    arguments.isEmpty() ? "{}" : arguments.toString());
        }
    }

    static boolean isTimeoutFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private RestClient createClient(ModelProvider provider) {
        try {
            String apiKey = secretCipher.decrypt(provider.encryptedApiKey());
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
            requestFactory.setReadTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl(normalizeBaseUrl(provider.baseUrl()))
                    .requestFactory(requestFactory);
            String authType = provider.authType() == null
                    ? "bearer" : provider.authType().trim().toLowerCase(Locale.ROOT);
            if ("none".equals(authType)) {
                return builder.build();
            }
            if ("bearer".equals(authType)) {
                return builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).build();
            }
            if ("x-api-key".equals(authType) || "api-key".equals(authType)) {
                return builder.defaultHeader("X-API-Key", apiKey).build();
            }
            throw new IllegalArgumentException("unsupported auth type");
        } catch (RuntimeException exception) {
            throw providerFailure(
                    ModelCallStatus.FAILED,
                    "INFERENCE_CLIENT_CONFIGURATION_FAILED",
                    "Inference provider client could not be prepared before dispatch",
                    exception);
        }
    }

    private Map<String, Object> requestBody(InferenceExecutionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.modelId());
        body.put("messages", request.messages().stream()
                .map(this::message)
                .toList());
        Object temperature = request.parameters().get("temperature");
        if (temperature instanceof Number number) {
            body.put("temperature", number.doubleValue());
        }
        Object maxOutputTokens = request.parameters().get("maxOutputTokens");
        if (maxOutputTokens instanceof Number number) {
            body.put("max_tokens", number.intValue());
        }
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream()
                    .map(tool -> Map.of(
                            "type", "function",
                            "function", Map.of(
                                    "name", tool.name(),
                                    "description", tool.description(),
                                    "parameters", tool.parameters())))
                    .toList());
        }
        return body;
    }

    private Map<String, String> message(InferenceMessage message) {
        return Map.of("role", message.role().toLowerCase(), "content", message.content());
    }

    private InferenceExecution parse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            if (!root.path("choices").isArray()
                    || root.path("choices").isEmpty()
                    || !root.path("choices").path(0).path("message").isObject()) {
                throw new IllegalArgumentException("missing response choice");
            }
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").isTextual() ? message.path("content").asText() : "";
            String reasoning = message.path("reasoning_content").isTextual()
                    ? message.path("reasoning_content").asText() : "";
            List<InferenceToolCall> toolCalls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                String callId = call.path("id").asText();
                String callName = call.path("function").path("name").asText();
                if (callId.isBlank() || callName.isBlank()) {
                    throw new IllegalArgumentException("invalid provider tool call");
                }
                toolCalls.add(new InferenceToolCall(
                        callId,
                        callName,
                        call.path("function").path("arguments").asText("{}")));
            }
            JsonNode usageNode = root.path("usage");
            Map<String, Object> usage = usageNode.isObject()
                    ? objectMapper.convertValue(usageNode, new TypeReference<Map<String, Object>>() { })
                    : Map.of();
            return new InferenceExecution(
                    content,
                    usageNode.path("prompt_tokens").asInt(0),
                    usageNode.path("completion_tokens").asInt(0),
                    toolCalls,
                    root.path("choices").path(0).path("finish_reason").asText(""),
                    root.path("id").isTextual() ? root.path("id").asText() : null,
                    usage,
                    reasoning);
        } catch (Exception exception) {
            throw providerFailure(
                    ModelCallStatus.UNKNOWN,
                    "INFERENCE_RESPONSE_AMBIGUOUS",
                    "Inference provider response was incomplete or invalid; completion is unknown",
                    exception);
        }
    }

    private void validateSerializable(Map<String, Object> body) {
        try {
            objectMapper.writeValueAsBytes(body);
        } catch (Exception exception) {
            throw providerFailure(
                    ModelCallStatus.FAILED,
                    "INFERENCE_REQUEST_INVALID",
                    "Inference request could not be serialized before provider dispatch",
                    exception);
        }
    }

    private static InferenceProviderException providerFailure(
            ModelCallStatus disposition,
            String code,
            String summary,
            Throwable cause) {
        return new InferenceProviderException(disposition, code, summary, cause);
    }

    static String safeProviderRejectionSummary(
            ObjectMapper objectMapper,
            int status,
            String responseBody) {
        String summary = "Inference provider returned HTTP " + status;
        if (responseBody == null || responseBody.isBlank()) {
            return summary;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode evidence = root.path("error").isObject() ? root.path("error") : root;
            String providerCode = safeProviderCode(evidence.path("code").asText(""));
            String providerMessage = safeProviderMessage(evidence.path("message").asText(""));
            if (!providerCode.isBlank()) {
                summary += " (" + providerCode + ")";
            }
            if (!providerMessage.isBlank()) {
                summary += ": " + providerMessage;
            }
        } catch (Exception ignored) {
            return summary;
        }
        return summary.length() <= 360 ? summary : summary.substring(0, 360);
    }

    private static String safeProviderCode(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("[A-Za-z0-9._:-]{1,80}") ? normalized : "";
    }

    private static String safeProviderMessage(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String safe = value
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+\\-/=]+", "Bearer [REDACTED]")
                .replaceAll("(?i)sk-[A-Za-z0-9_-]{6,}", "sk-[REDACTED]")
                .replaceAll(
                        "(?i)((?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|secret|password)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return safe.length() <= 240 ? safe : safe.substring(0, 240);
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return normalized;
    }
}
