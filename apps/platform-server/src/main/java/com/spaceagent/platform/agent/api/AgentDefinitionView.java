package com.spaceagent.platform.agent.api;

import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;

import java.time.Instant;
import java.util.List;

/** Canonical Agent identity with its immediately effective current configuration. */
public record AgentDefinitionView(
        String id,
        String ownerId,
        String tenantId,
        String name,
        String description,
        AgentDefinitionStatus status,
        String systemPrompt,
        String modelPoolId,
        String modelProviderId,
        String modelId,
        double temperature,
        int maxTokens,
        int maxTurns,
        String permissionMode,
        boolean memoryEnabled,
        boolean ragEnabled,
        boolean networkEnabled,
        List<String> knowledgeBaseIds,
        List<String> enabledToolIds,
        List<String> skillIds,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        List<String> knowledgeCollectionIds) {
    /** Source compatibility: legacy IDs remain document IDs. */
    public AgentDefinitionView(
            String id,
            String ownerId,
            String tenantId,
            String name,
            String description,
            AgentDefinitionStatus status,
            String systemPrompt,
            String modelPoolId,
            String modelProviderId,
            String modelId,
            double temperature,
            int maxTokens,
            int maxTurns,
            String permissionMode,
            boolean memoryEnabled,
            boolean ragEnabled,
            boolean networkEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt) {
        this(
                id, ownerId, tenantId, name, description,
                status, systemPrompt, modelPoolId, modelProviderId, modelId,
                temperature, maxTokens, maxTurns, permissionMode, memoryEnabled,
                ragEnabled, networkEnabled, knowledgeBaseIds, enabledToolIds, skillIds,
                revision, createdAt, updatedAt, archivedAt, List.of());
    }

}
