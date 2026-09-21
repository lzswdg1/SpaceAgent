package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable, secret-free effective Agent configuration captured once for one Runtime Run. */
public record AgentRunConfigurationSnapshot(
        String id,
        String runId,
        String tenantId,
        String ownerUserId,
        String agentId,
        State state,
        Long agentRevision,
        String configHash,
        String modelPoolId,
        String modelProviderId,
        String modelId,
        String systemPrompt,
        Double temperature,
        Integer maxContextTokens,
        Integer maxOutputTokens,
        Integer maxTurns,
        Boolean memoryEnabled,
        Boolean ragEnabled,
        Boolean networkEnabled,
        List<String> knowledgeBaseIds,
        List<String> enabledToolIds,
        List<String> skillIds,
        String permissionMode,
        List<McpBinding> mcpBindings,
        String sourceUpdatedBy,
        Instant sourceUpdatedAt,
        Instant capturedAt,
        List<String> knowledgeCollectionIds) {
    /** Source compatibility: legacy IDs remain document IDs. */
    public AgentRunConfigurationSnapshot(
            String id,
            String runId,
            String tenantId,
            String ownerUserId,
            String agentId,
            State state,
            Long agentRevision,
            String configHash,
            String modelPoolId,
            String modelProviderId,
            String modelId,
            String systemPrompt,
            Double temperature,
            Integer maxContextTokens,
            Integer maxOutputTokens,
            Integer maxTurns,
            Boolean memoryEnabled,
            Boolean ragEnabled,
            Boolean networkEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds,
            String permissionMode,
            List<McpBinding> mcpBindings,
            String sourceUpdatedBy,
            Instant sourceUpdatedAt,
            Instant capturedAt) {
        this(
                id, runId, tenantId, ownerUserId, agentId,
                state, agentRevision, configHash, modelPoolId, modelProviderId,
                modelId, systemPrompt, temperature, maxContextTokens, maxOutputTokens,
                maxTurns, memoryEnabled, ragEnabled, networkEnabled, knowledgeBaseIds,
                enabledToolIds, skillIds, permissionMode, mcpBindings, sourceUpdatedBy,
                sourceUpdatedAt, capturedAt, null);
    }


    public AgentRunConfigurationSnapshot {
        require(id, "id");
        require(runId, "runId");
        require(tenantId, "tenantId");
        require(ownerUserId, "ownerUserId");
        require(agentId, "agentId");
        Objects.requireNonNull(state, "state");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        if (state == State.SNAPSHOTTED) {
            knowledgeCollectionIds = normalized(knowledgeCollectionIds);
            if (agentRevision == null || agentRevision <= 0
                    || configHash == null || !configHash.matches("[0-9a-f]{64}")
                    || temperature == null || !Double.isFinite(temperature)
                    || temperature < 0.0 || temperature > 2.0
                    || maxContextTokens == null || maxContextTokens <= 0
                    || maxOutputTokens == null || maxOutputTokens <= 0
                    || maxTurns == null || maxTurns <= 0
                    || memoryEnabled == null || ragEnabled == null || networkEnabled == null
                    || sourceUpdatedAt == null) {
                throw new IllegalArgumentException("Run Agent configuration snapshot is incomplete");
            }
            if (modelPoolId != null && (modelProviderId != null || modelId != null)
                    || (modelProviderId == null) != (modelId == null)) {
                throw new IllegalArgumentException("Run Agent model binding is invalid");
            }
            require(permissionMode, "permissionMode");
            if (!permissionMode.matches("private|auto|ask|deny")) {
                throw new IllegalArgumentException("permissionMode is invalid");
            }
            require(sourceUpdatedBy, "sourceUpdatedBy");
            knowledgeBaseIds = normalized(knowledgeBaseIds);
            enabledToolIds = normalized(enabledToolIds);
            skillIds = normalized(skillIds);
            mcpBindings = mcpBindings == null ? List.of() : mcpBindings.stream()
                    .sorted(Comparator.comparing(McpBinding::sourceBindingId))
                    .toList();
            if (mcpBindings.stream().map(McpBinding::sourceBindingId).distinct().count()
                    != mcpBindings.size()) {
                throw new IllegalArgumentException("MCP binding ids must be unique");
            }
        } else {
            if (knowledgeCollectionIds != null && !knowledgeCollectionIds.isEmpty()
                    || agentRevision != null || configHash != null || systemPrompt != null || modelPoolId != null
                    || modelProviderId != null || modelId != null || temperature != null
                    || maxContextTokens != null || maxOutputTokens != null || maxTurns != null
                    || memoryEnabled != null || ragEnabled != null || networkEnabled != null
                    || knowledgeBaseIds != null || enabledToolIds != null || skillIds != null
                    || permissionMode != null || sourceUpdatedBy != null || sourceUpdatedAt != null
                    || mcpBindings != null && !mcpBindings.isEmpty()) {
                throw new IllegalArgumentException("Legacy unsnapshotted Run cannot fabricate configuration");
            }
            mcpBindings = List.of();
        }
    }

    public enum State {
        SNAPSHOTTED,
        LEGACY_UNSNAPSHOTTED
    }

    public record McpBinding(
            String sourceBindingId,
            String installationId,
            String connectionId,
            String serverVersionId,
            String capabilitySnapshotId,
            long connectionRevision,
            String snapshotSha256,
            List<String> allowedToolNames,
            String bindingSha256) {

        public McpBinding {
            require(sourceBindingId, "sourceBindingId");
            require(installationId, "installationId");
            require(connectionId, "connectionId");
            require(serverVersionId, "serverVersionId");
            require(capabilitySnapshotId, "capabilitySnapshotId");
            if (connectionRevision <= 0) {
                throw new IllegalArgumentException("connectionRevision must be positive");
            }
            prefixedHash(snapshotSha256, "snapshotSha256");
            prefixedHash(bindingSha256, "bindingSha256");
            allowedToolNames = normalized(allowedToolNames);
            if (allowedToolNames.isEmpty() || allowedToolNames.size() > 100) {
                throw new IllegalArgumentException("allowedToolNames is invalid");
            }
        }
    }

    private static List<String> normalized(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().sorted().toList();
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
