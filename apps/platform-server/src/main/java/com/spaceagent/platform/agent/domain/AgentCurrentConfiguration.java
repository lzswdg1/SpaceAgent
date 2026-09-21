package com.spaceagent.platform.agent.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The one effective, secret-free configuration of an Agent.
 *
 * <p>Runtime copies this value into an immutable snapshot when a Run starts, so later saves
 * cannot alter historical execution evidence.</p>
 */
public record AgentCurrentConfiguration(
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
        List<McpBinding> mcpBindings,
        String updatedBy,
        Instant updatedAt,
        List<String> knowledgeCollectionIds) {
    /** Source compatibility: legacy IDs remain document IDs. */
    public AgentCurrentConfiguration(
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
            List<McpBinding> mcpBindings,
            String updatedBy,
            Instant updatedAt) {
        this(
                agentId, ownerUserId, tenantId, agentRevision, configHash,
                modelPoolId, modelProviderId, modelId, systemPrompt, temperature,
                maxContextTokens, maxOutputTokens, maxTurns, memoryEnabled, ragEnabled,
                networkEnabled, knowledgeBaseIds, enabledToolIds, skillIds, permissionMode,
                mcpBindings, updatedBy, updatedAt, List.of());
    }


    public AgentCurrentConfiguration {
        knowledgeCollectionIds = knowledgeCollectionIds == null ? List.of() : List.copyOf(knowledgeCollectionIds);
        if (knowledgeCollectionIds.size() > 64 || knowledgeCollectionIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 36))
            throw new IllegalArgumentException("Invalid knowledge collection bindings");
        require(agentId, "agentId");
        require(ownerUserId, "ownerUserId");
        require(tenantId, "tenantId");
        if (agentRevision <= 0) {
            throw new IllegalArgumentException("agentRevision must be positive");
        }
        if (configHash == null || !configHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("configHash must be a lowercase SHA-256 value");
        }
        if (maxContextTokens <= 0 || maxOutputTokens <= 0 || maxTurns <= 0) {
            throw new IllegalArgumentException("Agent configuration token and turn limits must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (modelPoolId != null && (modelProviderId != null || modelId != null)) {
            throw new IllegalArgumentException(
                    "modelPoolId cannot coexist with direct provider/model binding");
        }
        if ((modelProviderId == null) != (modelId == null)) {
            throw new IllegalArgumentException(
                    "modelProviderId and modelId must be configured together");
        }
        knowledgeBaseIds = normalizedIds(knowledgeBaseIds);
        enabledToolIds = normalizedIds(enabledToolIds);
        skillIds = normalizedIds(skillIds);
        mcpBindings = mcpBindings == null ? List.of() : mcpBindings.stream()
                .sorted(Comparator.comparing(McpBinding::id))
                .toList();
        if (mcpBindings.stream().map(McpBinding::id).distinct().count() != mcpBindings.size()) {
            throw new IllegalArgumentException("MCP binding ids must be unique");
        }
        require(permissionMode, "permissionMode");
        if (!permissionMode.matches("private|auto|ask|deny")) {
            throw new IllegalArgumentException("permissionMode is invalid");
        }
        require(updatedBy, "updatedBy");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public record McpBinding(
            String id,
            String installationId,
            String connectionId,
            String serverVersionId,
            String capabilitySnapshotId,
            long connectionRevision,
            String snapshotSha256,
            List<String> allowedToolNames,
            String bindingSha256) {

        public McpBinding {
            require(id, "id");
            require(installationId, "installationId");
            require(connectionId, "connectionId");
            require(serverVersionId, "serverVersionId");
            require(capabilitySnapshotId, "capabilitySnapshotId");
            if (connectionRevision <= 0) {
                throw new IllegalArgumentException("connectionRevision must be positive");
            }
            prefixedHash(snapshotSha256, "snapshotSha256");
            prefixedHash(bindingSha256, "bindingSha256");
            allowedToolNames = normalizedIds(allowedToolNames);
            if (allowedToolNames.isEmpty() || allowedToolNames.size() > 100) {
                throw new IllegalArgumentException("allowedToolNames is invalid");
            }
        }
    }

    private static List<String> normalizedIds(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void prefixedHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be SHA-256");
        }
    }
}
