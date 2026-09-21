package com.spaceagent.platform.agent.domain;

import java.util.List;

/** Complete normalized replacement proposed for an Agent's single current configuration. */
public record AgentConfigurationProposal(
        String name,
        String description,
        String systemPrompt,
        String modelPoolId,
        String modelProviderId,
        String modelId,
        double temperature,
        int maxContextTokens,
        int maxOutputTokens,
        int maxTurns,
        String permissionMode,
        boolean memoryEnabled,
        boolean ragEnabled,
        boolean networkEnabled,
        List<String> knowledgeBaseIds,
        List<String> enabledToolIds,
        List<String> skillIds,
        String configHash,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<String> knowledgeCollectionIds) {
    /** Source compatibility: legacy IDs remain document IDs. */
    public AgentConfigurationProposal(
            String name,
            String description,
            String systemPrompt,
            String modelPoolId,
            String modelProviderId,
            String modelId,
            double temperature,
            int maxContextTokens,
            int maxOutputTokens,
            int maxTurns,
            String permissionMode,
            boolean memoryEnabled,
            boolean ragEnabled,
            boolean networkEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds,
            String configHash) {
        this(
                name, description, systemPrompt, modelPoolId, modelProviderId,
                modelId, temperature, maxContextTokens, maxOutputTokens, maxTurns,
                permissionMode, memoryEnabled, ragEnabled, networkEnabled, knowledgeBaseIds,
                enabledToolIds, skillIds, configHash, List.of());
    }


    public AgentConfigurationProposal {
        knowledgeCollectionIds = knowledgeCollectionIds == null ? List.of() : List.copyOf(knowledgeCollectionIds);
        if (knowledgeCollectionIds.size() > 64 || knowledgeCollectionIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 36))
            throw new IllegalArgumentException("Invalid knowledge collection bindings");
        name = requireText(name, "name");
        modelPoolId = normalize(modelPoolId);
        modelProviderId = normalize(modelProviderId);
        modelId = normalize(modelId);
        permissionMode = requireText(permissionMode, "permissionMode");
        knowledgeBaseIds = immutable(knowledgeBaseIds);
        enabledToolIds = immutable(enabledToolIds);
        skillIds = immutable(skillIds);
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxContextTokens <= 0 || maxOutputTokens <= 0 || maxTurns <= 0) {
            throw new IllegalArgumentException("Agent configuration limits must be positive");
        }
        if (!permissionMode.matches("private|auto|ask|deny")) {
            throw new IllegalArgumentException("permissionMode is invalid");
        }
        if (modelPoolId != null && (modelProviderId != null || modelId != null)) {
            throw new IllegalArgumentException("ModelPool and direct model binding cannot coexist");
        }
        if ((modelProviderId == null) != (modelId == null)) {
            throw new IllegalArgumentException("Direct model binding must be complete");
        }
        requireHash(configHash, "configHash", false);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    static String requireHash(String value, String field, boolean prefixed) {
        String expression = prefixed ? "sha256:[0-9a-f]{64}" : "[0-9a-f]{64}";
        if (value == null || !value.matches(expression)) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return value;
    }
}
