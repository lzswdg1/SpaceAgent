package com.spaceagent.platform.agent.api;

import java.util.List;

/**
 * Read-only runtime configuration snapshot. It contains provider/model references,
 * never provider credentials or execution state.
 */
public record AgentRuntimeConfigurationView(
        String agentId,
        String ownerUserId,
        String tenantId,
        String modelPoolId,
        String modelProviderId,
        String modelId,
        String systemPrompt,
        double temperature,
        int maxTokens,
        int maxTurns,
        boolean memoryEnabled,
        boolean ragEnabled,
        List<String> knowledgeBaseIds,
        List<String> enabledToolIds,
        List<String> skillIds,
        String permissionMode,
        boolean networkEnabled,
        long agentRevision,
        List<String> knowledgeCollectionIds) {
    /** Source compatibility: legacy IDs remain document IDs. */
    public AgentRuntimeConfigurationView(
            String agentId,
            String ownerUserId,
            String tenantId,
            String modelPoolId,
            String modelProviderId,
            String modelId,
            String systemPrompt,
            double temperature,
            int maxTokens,
            int maxTurns,
            boolean memoryEnabled,
            boolean ragEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds,
            String permissionMode,
            boolean networkEnabled,
            long agentRevision) {
        this(
                agentId, ownerUserId, tenantId, modelPoolId, modelProviderId,
                modelId, systemPrompt, temperature, maxTokens, maxTurns,
                memoryEnabled, ragEnabled, knowledgeBaseIds, enabledToolIds, skillIds,
                permissionMode, networkEnabled, agentRevision, List.of());
    }

}
