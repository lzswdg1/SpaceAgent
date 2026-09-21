package com.spaceagent.platform.runtime.api;

import java.time.Instant;
import java.util.List;

/** Secret-free immutable configuration projected from one Runtime-owned Run snapshot. */
public record AgentExecutionConfigurationView(
        String agentId, String configHash, String modelPoolId, String modelProviderId,
        String modelId, String systemPrompt, double temperature, int maxContextTokens,
        int maxOutputTokens, int maxTurns, boolean memoryEnabled, boolean ragEnabled,
        List<String> knowledgeBaseIds, List<String> enabledToolIds, List<String> skillIds,
        String permissionMode, boolean networkEnabled, long sourceAgentRevision,
        List<McpBindingView> mcpBindings,
        List<String> knowledgeCollectionIds) {
    /** Source compatibility: legacy IDs remain document IDs. */
    public AgentExecutionConfigurationView(
            String agentId,
            String configHash,
            String modelPoolId,
            String modelProviderId,
            String modelId,
            String systemPrompt,
            double temperature,
            int maxContextTokens,
            int maxOutputTokens,
            int maxTurns,
            boolean memoryEnabled,
            boolean ragEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds,
            String permissionMode,
            boolean networkEnabled,
            long sourceAgentRevision,
            List<McpBindingView> mcpBindings) {
        this(
                agentId, configHash, modelPoolId, modelProviderId, modelId,
                systemPrompt, temperature, maxContextTokens, maxOutputTokens, maxTurns,
                memoryEnabled, ragEnabled, knowledgeBaseIds, enabledToolIds, skillIds,
                permissionMode, networkEnabled, sourceAgentRevision, mcpBindings, List.of());
    }

    public AgentExecutionConfigurationView {
        knowledgeCollectionIds = knowledgeCollectionIds == null ? List.of() : List.copyOf(knowledgeCollectionIds);
        if (knowledgeCollectionIds.size() > 64 || knowledgeCollectionIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 36))
            throw new IllegalArgumentException("Invalid knowledge collection bindings");
        knowledgeBaseIds = copy(knowledgeBaseIds);
        enabledToolIds = copy(enabledToolIds);
        skillIds = copy(skillIds);
        mcpBindings = mcpBindings == null ? List.of() : List.copyOf(mcpBindings);
    }

    public record McpBindingView(
            String sourceBindingId, String agentId, String installationId, String connectionId,
            String serverVersionId, String capabilitySnapshotId, long connectionRevision,
            String snapshotSha256, List<String> allowedToolNames, String bindingSha256,
            String sourceUpdatedBy, Instant sourceUpdatedAt) {
        public McpBindingView {
            allowedToolNames = copy(allowedToolNames);
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
