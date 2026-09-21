package com.spaceagent.platform.inference.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** SHA-256 over the normalized, secret-free model request semantics. */
@Component
public class ModelCallRequestHasher {

    private final ObjectMapper canonicalMapper;

    public ModelCallRequestHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public String hash(InferenceExecutor.InferenceExecutionRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("providerId", request.providerType());
        canonical.put("modelId", request.modelId());
        canonical.put("messages", request.messages().stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList());
        canonical.put("tools", request.tools().stream()
                .map(tool -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("name", tool.name());
                    value.put("description", tool.description());
                    value.put("parameters", normalize(tool.parameters()));
                    return value;
                })
                .toList());
        canonical.put("parameters", normalize(request.parameters()));
        try {
            byte[] bytes = canonicalMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("unable to create deterministic model request hash", exception);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ModelCallRequestHasher::normalize).toList();
        }
        if (value instanceof Object[] array) {
            return java.util.Arrays.stream(array).map(ModelCallRequestHasher::normalize).toList();
        }
        return value;
    }
}
