package com.spaceagent.platform.runtime.api;

import java.time.Instant;
import java.util.List;

/** Public Runtime read boundary for one immutable per-Run Agent configuration snapshot. */
public interface AgentRunConfigurationSnapshotApplicationApi {

    SnapshotView require(String tenantId, String ownerUserId, String runId);

    record SnapshotView(
            String id,
            String runId,
            String tenantId,
            String ownerUserId,
            String agentId,
            String state,
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
            List<McpBindingView> mcpBindings,
            String sourceUpdatedBy,
            Instant sourceUpdatedAt,
            Instant capturedAt) {

        public SnapshotView {
            knowledgeBaseIds = copy(knowledgeBaseIds);
            enabledToolIds = copy(enabledToolIds);
            skillIds = copy(skillIds);
            mcpBindings = mcpBindings == null ? List.of() : List.copyOf(mcpBindings);
        }
    }

    record McpBindingView(
            String sourceBindingId,
            String installationId,
            String connectionId,
            String serverVersionId,
            String capabilitySnapshotId,
            long connectionRevision,
            String snapshotSha256,
            List<String> allowedToolNames,
            String bindingSha256) {

        public McpBindingView {
            allowedToolNames = copy(allowedToolNames);
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
