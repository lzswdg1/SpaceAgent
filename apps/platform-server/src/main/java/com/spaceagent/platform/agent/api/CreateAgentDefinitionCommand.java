package com.spaceagent.platform.agent.api;

import java.util.List;

/** Creates a canonical Agent with one immediately effective current configuration. */
public record CreateAgentDefinitionCommand(
        String ownerId,
        String tenantId,
        String name,
        String description,
        String systemPrompt,
        String modelPoolId,
        String modelProviderId,
        String modelId,
        Double temperature,
        Integer maxTokens,
        Integer maxTurns,
        String permissionMode,
        Boolean memoryEnabled,
        Boolean ragEnabled,
        Boolean networkEnabled,
        List<String> knowledgeBaseIds,
        List<String> enabledToolIds,
        List<String> skillIds,
        List<String> knowledgeCollectionIds) {
    /** Source compatibility: legacy IDs remain document IDs. */
    public CreateAgentDefinitionCommand(
            String ownerId,
            String tenantId,
            String name,
            String description,
            String systemPrompt,
            String modelPoolId,
            String modelProviderId,
            String modelId,
            Double temperature,
            Integer maxTokens,
            Integer maxTurns,
            String permissionMode,
            Boolean memoryEnabled,
            Boolean ragEnabled,
            Boolean networkEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds) {
        this(
                ownerId, tenantId, name, description, systemPrompt,
                modelPoolId, modelProviderId, modelId, temperature, maxTokens,
                maxTurns, permissionMode, memoryEnabled, ragEnabled, networkEnabled,
                knowledgeBaseIds, enabledToolIds, skillIds, List.of());
    }


    public CreateAgentDefinitionCommand {
        knowledgeCollectionIds = knowledgeCollectionIds == null ? List.of() : List.copyOf(knowledgeCollectionIds);
        if (knowledgeCollectionIds.size() > 64 || knowledgeCollectionIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 36))
            throw new IllegalArgumentException("Invalid knowledge collection bindings");
        requireNonBlank(ownerId, "ownerId");
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(name, "name");
    }

    /** Compatibility constructor for clients that still bind a direct Provider/Model. */
    public CreateAgentDefinitionCommand(
            String ownerId,
            String tenantId,
            String name,
            String description,
            String systemPrompt,
            String modelProviderId,
            String modelId,
            Double temperature,
            Integer maxTokens,
            Integer maxTurns,
            String permissionMode,
            Boolean memoryEnabled,
            Boolean ragEnabled,
            Boolean networkEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds) {
        this(ownerId, tenantId, name, description, systemPrompt, null,
                modelProviderId, modelId, temperature, maxTokens, maxTurns,
                permissionMode, memoryEnabled, ragEnabled, networkEnabled,
                knowledgeBaseIds, enabledToolIds, skillIds);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
