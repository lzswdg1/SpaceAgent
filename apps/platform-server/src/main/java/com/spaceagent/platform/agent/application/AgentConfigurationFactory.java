package com.spaceagent.platform.agent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import com.spaceagent.platform.agent.domain.AgentConfigurationProposal;

/** Normalizes and hashes the one immediately effective, secret-free Agent configuration. */
@Component
public class AgentConfigurationFactory {

    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 200_000;

    private final ObjectMapper objectMapper;

    public AgentConfigurationFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(ConfigurationInput input) {
        String poolBinding = input.modelPoolId() == null
                ? "" : "modelPoolId:" + jsonString(input.modelPoolId()) + "\n";
        String canonical = "systemPrompt:" + jsonString(input.systemPrompt() == null
                ? "" : input.systemPrompt()) + "\n" + poolBinding + String.join("\n",
                "modelProviderId:" + nullableJsonString(input.modelProviderId()),
                "modelId:" + nullableJsonString(input.modelId()),
                "temperature:" + stableNumber(input.temperature()),
                "maxContextTokens:" + DEFAULT_MAX_CONTEXT_TOKENS,
                "maxOutputTokens:" + input.maxOutputTokens(),
                "maxTurns:" + input.maxTurns(),
                "permissionMode:" + jsonString(input.permissionMode()),
                "memoryEnabled:" + input.memoryEnabled(),
                "ragEnabled:" + input.ragEnabled(),
                "networkEnabled:" + input.networkEnabled(),
                "knowledgeBaseIds:" + jsonArray(input.knowledgeBaseIds()),
                "enabledToolIds:" + jsonArray(input.enabledToolIds()),
                "skillIds:" + jsonArray(input.skillIds()));
        if (!input.knowledgeCollectionIds().isEmpty()) canonical += "\nknowledgeCollectionIds:" + jsonArray(input.knowledgeCollectionIds().stream().distinct().sorted().toList());
        return digest(canonical);
    }

    public String proposalHash(AgentConfigurationProposal proposal) {
        try {
            return "sha256:" + digest(objectMapper.writeValueAsString(proposal));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to normalize Agent proposal", exception);
        }
    }

    public static ConfigurationInput fromCurrent(
            com.spaceagent.platform.agent.domain.AgentCurrentConfiguration value) {
        return new ConfigurationInput(
                value.systemPrompt(), value.modelPoolId(), value.modelProviderId(), value.modelId(),
                value.temperature(), value.maxOutputTokens(), value.maxTurns(),
                value.permissionMode(), value.memoryEnabled(), value.ragEnabled(),
                value.networkEnabled(), value.knowledgeBaseIds(), value.enabledToolIds(),
                value.skillIds(), value.knowledgeCollectionIds());
    }

    private String jsonArray(List<String> values) {
        return values.stream().map(this::jsonString)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private String nullableJsonString(String value) {
        return value == null ? "null" : jsonString(value);
    }

    private String jsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to normalize Agent configuration text", exception);
        }
    }

    private static String stableNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

public record ConfigurationInput(
            String systemPrompt, String modelPoolId, String modelProviderId, String modelId,
            double temperature, int maxOutputTokens, int maxTurns, String permissionMode,
            boolean memoryEnabled, boolean ragEnabled, boolean networkEnabled,
            List<String> knowledgeBaseIds, List<String> enabledToolIds, List<String> skillIds,
        List<String> knowledgeCollectionIds) {
        /** Source compatibility: legacy IDs remain document IDs. */
        public ConfigurationInput(
                String systemPrompt,
                String modelPoolId,
                String modelProviderId,
                String modelId,
                double temperature,
                int maxOutputTokens,
                int maxTurns,
                String permissionMode,
                boolean memoryEnabled,
                boolean ragEnabled,
                boolean networkEnabled,
                List<String> knowledgeBaseIds,
                List<String> enabledToolIds,
                List<String> skillIds) {
            this(
                    systemPrompt, modelPoolId, modelProviderId, modelId, temperature,
                    maxOutputTokens, maxTurns, permissionMode, memoryEnabled, ragEnabled,
                    networkEnabled, knowledgeBaseIds, enabledToolIds, skillIds, List.of());
        }

    public ConfigurationInput {
        knowledgeCollectionIds = knowledgeCollectionIds == null ? List.of() : List.copyOf(knowledgeCollectionIds);
        if (knowledgeCollectionIds.size() > 64 || knowledgeCollectionIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 36))
            throw new IllegalArgumentException("Invalid knowledge collection bindings");
            knowledgeBaseIds = normalize(knowledgeBaseIds);
            enabledToolIds = normalize(enabledToolIds);
            skillIds = normalize(skillIds);
        }

        private static List<String> normalize(List<String> values) {
            if (values == null) return List.of();
            return values.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).distinct().sorted().toList();
        }
    }
}
