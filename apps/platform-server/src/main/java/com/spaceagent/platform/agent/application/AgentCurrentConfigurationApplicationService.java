package com.spaceagent.platform.agent.application;

import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.agent.domain.AgentCurrentConfiguration;
import com.spaceagent.platform.agent.domain.AgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.domain.AgentDefinition;
import com.spaceagent.platform.agent.domain.AgentRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolver for the one immediately effective Agent current-configuration boundary.
 */
@Service
public class AgentCurrentConfigurationApplicationService
        implements AgentCurrentConfigurationApplicationApi {

    private final AgentRepository agents;
    private final AgentCurrentConfigurationRepository currentConfigurations;

    public AgentCurrentConfigurationApplicationService(
            AgentRepository agents,
            AgentCurrentConfigurationRepository currentConfigurations) {
        this.agents = agents;
        this.currentConfigurations = currentConfigurations;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigurationView> findCurrent(
            String tenantId, String ownerUserId, String agentId) {
        Optional<AgentDefinition> agent = agents.findById(agentId)
                .filter(definition -> tenantId.equals(definition.tenantId()))
                .filter(definition -> ownerUserId.equals(definition.ownerId()));
        if (agent.isEmpty()) return Optional.empty();
        Optional<AgentCurrentConfiguration> stored = currentConfigurations.find(
                tenantId, ownerUserId, agentId);
        return stored.map(AgentCurrentConfigurationApplicationService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public ConfigurationView requireCurrent(
            String tenantId, String ownerUserId, String agentId) {
        AgentDefinition agent = agents.findById(agentId)
                .filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> ownerUserId.equals(value.ownerId()))
                .orElseThrow(AgentCurrentConfigurationApplicationService::notFound);
        Optional<AgentCurrentConfiguration> stored = currentConfigurations.find(
                tenantId, ownerUserId, agentId);
        return stored.map(AgentCurrentConfigurationApplicationService::view)
                .orElseThrow(AgentCurrentConfigurationApplicationService::configurationMissing);
    }

    private static ConfigurationView view(AgentCurrentConfiguration value) {
        return new ConfigurationView(
                value.agentId(), value.ownerUserId(), value.tenantId(), value.agentRevision(),
                value.configHash(), value.modelPoolId(), value.modelProviderId(), value.modelId(),
                value.systemPrompt(), value.temperature(), value.maxContextTokens(),
                value.maxOutputTokens(), value.maxTurns(), value.memoryEnabled(), value.ragEnabled(),
                value.networkEnabled(), value.knowledgeBaseIds(), value.enabledToolIds(),
                value.skillIds(), value.permissionMode(), value.mcpBindings().stream()
                        .map(binding -> new McpBindingView(
                                binding.id(), binding.installationId(), binding.connectionId(),
                                binding.serverVersionId(), binding.capabilitySnapshotId(),
                                binding.connectionRevision(), binding.snapshotSha256(),
                                binding.allowedToolNames(), binding.bindingSha256()))
                        .toList(),
                value.updatedBy(), value.updatedAt(), value.knowledgeCollectionIds());
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "Agent not found", HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND");
    }

    private static BusinessException configurationMissing() {
        return new BusinessException(
                "Agent current configuration is missing",
                HttpStatus.CONFLICT,
                "AGENT_CURRENT_CONFIGURATION_MISSING");
    }
}
