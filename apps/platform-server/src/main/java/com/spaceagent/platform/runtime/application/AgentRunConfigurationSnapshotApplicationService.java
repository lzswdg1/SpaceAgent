package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.runtime.api.AgentRunConfigurationSnapshotApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshot;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshotRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunConfigurationSnapshotApplicationService
        implements AgentRunConfigurationSnapshotApplicationApi {

    private final AgentRunConfigurationSnapshotRepository snapshots;

    public AgentRunConfigurationSnapshotApplicationService(
            AgentRunConfigurationSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    @Override
    @Transactional(readOnly = true)
    public SnapshotView require(String tenantId, String ownerUserId, String runId) {
        return snapshots.findByRunId(tenantId, ownerUserId, runId)
                .map(AgentRunConfigurationSnapshotApplicationService::view)
                .orElseThrow(() -> new BusinessException(
                        "Run Agent configuration snapshot not found",
                        HttpStatus.NOT_FOUND,
                        "RUN_AGENT_CONFIGURATION_NOT_FOUND"));
    }

    private static SnapshotView view(AgentRunConfigurationSnapshot value) {
        return new SnapshotView(
                value.id(), value.runId(), value.tenantId(), value.ownerUserId(), value.agentId(),
                value.state().name(), value.agentRevision(), value.configHash(), value.modelPoolId(),
                value.modelProviderId(), value.modelId(), value.systemPrompt(), value.temperature(),
                value.maxContextTokens(), value.maxOutputTokens(), value.maxTurns(),
                value.memoryEnabled(), value.ragEnabled(), value.networkEnabled(),
                value.knowledgeBaseIds(), value.enabledToolIds(), value.skillIds(),
                value.permissionMode(), value.mcpBindings().stream()
                        .map(binding -> new McpBindingView(
                                binding.sourceBindingId(), binding.installationId(),
                                binding.connectionId(), binding.serverVersionId(),
                                binding.capabilitySnapshotId(), binding.connectionRevision(),
                                binding.snapshotSha256(), binding.allowedToolNames(),
                                binding.bindingSha256()))
                        .toList(),
                value.sourceUpdatedBy(), value.sourceUpdatedAt(), value.capturedAt());
    }
}
