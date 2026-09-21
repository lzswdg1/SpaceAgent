package com.spaceagent.platform.agent.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Public read boundary for the one effective, secret-free Agent configuration. */
public interface AgentCurrentConfigurationApplicationApi {

    Optional<ConfigurationView> findCurrent(String tenantId, String ownerUserId, String agentId);

    ConfigurationView requireCurrent(String tenantId, String ownerUserId, String agentId);

record ConfigurationView(
            String agentId,
            String ownerUserId,
            String tenantId,
            long agentRevision,
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
            boolean networkEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds,
            String permissionMode,
            List<McpBindingView> mcpBindings,
            String updatedBy,
            Instant updatedAt,
        List<String> knowledgeCollectionIds) {
        /** Source compatibility: legacy IDs remain document IDs. */
        public ConfigurationView(
                String agentId,
                String ownerUserId,
                String tenantId,
                long agentRevision,
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
                boolean networkEnabled,
                List<String> knowledgeBaseIds,
                List<String> enabledToolIds,
                List<String> skillIds,
                String permissionMode,
                List<McpBindingView> mcpBindings,
                String updatedBy,
                Instant updatedAt) {
            this(
                    agentId, ownerUserId, tenantId, agentRevision, configHash,
                    modelPoolId, modelProviderId, modelId, systemPrompt, temperature,
                    maxContextTokens, maxOutputTokens, maxTurns, memoryEnabled, ragEnabled,
                    networkEnabled, knowledgeBaseIds, enabledToolIds, skillIds, permissionMode,
                    mcpBindings, updatedBy, updatedAt, List.of());
        }


    public ConfigurationView {
        knowledgeCollectionIds = knowledgeCollectionIds == null ? List.of() : List.copyOf(knowledgeCollectionIds);
        if (knowledgeCollectionIds.size() > 64 || knowledgeCollectionIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 36))
            throw new IllegalArgumentException("Invalid knowledge collection bindings");
            knowledgeBaseIds = immutable(knowledgeBaseIds);
            enabledToolIds = immutable(enabledToolIds);
            skillIds = immutable(skillIds);
            mcpBindings = mcpBindings == null ? List.of() : List.copyOf(mcpBindings);
        }
    }

    record McpBindingView(
            String id,
            String installationId,
            String connectionId,
            String serverVersionId,
            String capabilitySnapshotId,
            long connectionRevision,
            String snapshotSha256,
            List<String> allowedToolNames,
            String bindingSha256) {

        public McpBindingView {
            allowedToolNames = immutable(allowedToolNames);
        }
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
