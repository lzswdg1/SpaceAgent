package com.spaceagent.platform.agent.application;

import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.api.AgentOwnershipPort;
import com.spaceagent.platform.agent.api.AgentRuntimeConfigurationView;
import com.spaceagent.platform.agent.api.CreateAgentDefinitionCommand;
import com.spaceagent.platform.agent.api.UpdateAgentDefinitionCommand;
import com.spaceagent.platform.agent.application.AgentConfigurationFactory.ConfigurationInput;
import com.spaceagent.platform.agent.domain.AgentDefinition;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.agent.domain.AgentCurrentConfiguration;
import com.spaceagent.platform.agent.domain.AgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.domain.AgentConfigurationProposal;
import com.spaceagent.platform.agent.domain.AgentRepository;
import com.spaceagent.platform.inference.api.InferenceApplicationApi;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeOwnershipPort;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.Optional;

/** Application coordinator for the single canonical AgentDefinition aggregate. */
@Service
public class AgentApplicationService implements AgentApplicationApi, AgentOwnershipPort {

    private final AgentRepository agentRepository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final InferenceApplicationApi inferenceApi;
    private final ModelPoolApplicationApi modelPoolApi;
    private final KnowledgeOwnershipPort knowledgeOwnershipPort;
    private final AgentConfigurationFactory configurationFactory;
    private final RuntimeCapabilityCatalogApplicationApi capabilityCatalogApi;
    private final AgentCurrentConfigurationRepository currentConfigurations;
    private com.spaceagent.platform.knowledge.api.KnowledgeAccessApplicationApi knowledgeAccess;

    @Autowired
    public void configureKnowledgeAccess(com.spaceagent.platform.knowledge.api.KnowledgeAccessApplicationApi knowledgeAccess) {
        this.knowledgeAccess = knowledgeAccess;
    }

    @Autowired
    public AgentApplicationService(
            AgentRepository agentRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            InferenceApplicationApi inferenceApi,
            ModelPoolApplicationApi modelPoolApi,
            KnowledgeOwnershipPort knowledgeOwnershipPort,
            AgentConfigurationFactory configurationFactory,
            RuntimeCapabilityCatalogApplicationApi capabilityCatalogApi,
            AgentCurrentConfigurationRepository currentConfigurations) {
        this.agentRepository = agentRepository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.inferenceApi = inferenceApi;
        this.modelPoolApi = modelPoolApi;
        this.knowledgeOwnershipPort = knowledgeOwnershipPort;
        this.configurationFactory = configurationFactory;
        this.capabilityCatalogApi = capabilityCatalogApi;
        this.currentConfigurations = currentConfigurations;
    }

    @Override
    @Transactional
    public AgentDefinitionView create(CreateAgentDefinitionCommand command) {
        String name = command.name().trim();
        String poolId = blankToNull(command.modelPoolId());
        String providerId = blankToNull(command.modelProviderId());
        String modelId = blankToNull(command.modelId());
        ConfigurationInput input = new ConfigurationInput(
                command.systemPrompt(), poolId, providerId, modelId,
                command.temperature() == null ? 0.7 : command.temperature(),
                command.maxTokens() == null ? 4096 : command.maxTokens(),
                command.maxTurns() == null ? 25 : command.maxTurns(),
                defaultPermission(command.permissionMode()),
                command.memoryEnabled() == null || command.memoryEnabled(),
                Boolean.TRUE.equals(command.ragEnabled()),
                Boolean.TRUE.equals(command.networkEnabled()),
                command.knowledgeBaseIds(), command.enabledToolIds(), command.skillIds(), command.knowledgeCollectionIds());
        validateValues(name, input);
        validateCapabilities(command.tenantId(), input);
        rejectDuplicateName(command.tenantId(), name, null);
        validateReferences(
                command.tenantId(), command.ownerId(), poolId, providerId, modelId,
                input.knowledgeBaseIds());
        validateCollections(command.tenantId(), command.ownerId(), command.ownerId(), command.knowledgeCollectionIds());

        Instant now = timeProvider.now();
        AgentDefinition definition = new AgentDefinition(
                idGenerator.nextId(), command.ownerId(), command.tenantId(), name,
                command.description(), AgentDefinitionStatus.ACTIVE, 1L,
                now, now, null);
        agentRepository.save(definition);
        AgentCurrentConfiguration configuration = currentConfiguration(
                definition, input, configurationFactory.hash(input), List.of(),
                definition.ownerId(), now);
        currentConfigurations.insert(configuration);
        return toView(definition, configuration);
    }

    @Override
    public Optional<AgentDefinitionView> findById(String agentId) {
        return agentRepository.findById(agentId).map(this::toView);
    }

    @Override
    public List<AgentDefinitionView> listByOwner(String ownerId) {
        return toViews(agentRepository.findByOwnerId(ownerId));
    }

    @Override
    public List<AgentDefinitionView> listByTenantAndOwner(String tenantId, String ownerId) {
        return toViews(agentRepository.findByTenantAndOwnerId(tenantId, ownerId));
    }

    @Override
    public List<AgentDefinitionView> listByTenant(String tenantId) {
        return toViews(agentRepository.findByTenantId(tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentDefinitionView> page(String tenantId, String ownerId, int offset, int limit) {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100)
            throw new BusinessException("Invalid Agent page bounds", HttpStatus.BAD_REQUEST);
        return toViews(agentRepository.findPage(tenantId, ownerId, offset, limit));
    }

    @Override
    @Transactional
    public AgentDefinitionView update(UpdateAgentDefinitionCommand command) {
        PreparedAgentChange prepared = prepareChange(command);
        requireOwner(prepared.definition(), command.tenantId(), command.ownerId());
        return applyPrepared(prepared, command.ownerId());
    }

    @Override
    @Transactional
    public AgentDefinitionView updateAsOrganizationOwner(UpdateAgentDefinitionCommand command) {
        PreparedAgentChange prepared = prepareChange(command);
        requireTenant(prepared.definition(), command.tenantId());
        return applyPrepared(prepared, command.ownerId());
    }

    PreparedAgentChange prepareChange(UpdateAgentDefinitionCommand command) {
        AgentDefinition current = agentRepository.findByIdForUpdate(command.agentId())
                .orElseThrow(() -> new BusinessException(
                        "Agent not found: " + command.agentId(), HttpStatus.NOT_FOUND));
        requireTenant(current, command.tenantId());

        AgentCurrentConfiguration previousConfiguration = currentConfigurations.find(
                current.tenantId(), current.ownerId(), current.id())
                .orElseThrow(() -> new BusinessException(
                        "Agent current configuration is missing", HttpStatus.CONFLICT,
                        "AGENT_CURRENT_CONFIGURATION_MISSING"));
        ConfigurationInput previous = AgentConfigurationFactory.fromCurrent(previousConfiguration);
        ModelSelection selection = updatedSelection(command, previous);
        ConfigurationInput updatedInput = new ConfigurationInput(
                command.systemPrompt() == null ? previous.systemPrompt() : command.systemPrompt(),
                selection.modelPoolId(), selection.modelProviderId(), selection.modelId(),
                command.temperature() == null ? previous.temperature() : command.temperature(),
                command.maxTokens() == null ? previous.maxOutputTokens() : command.maxTokens(),
                command.maxTurns() == null ? previous.maxTurns() : command.maxTurns(),
                command.permissionMode() == null ? previous.permissionMode() : command.permissionMode(),
                command.memoryEnabled() == null ? previous.memoryEnabled() : command.memoryEnabled(),
                command.ragEnabled() == null ? previous.ragEnabled() : command.ragEnabled(),
                command.networkEnabled() == null ? previous.networkEnabled() : command.networkEnabled(),
                command.knowledgeBaseIds() == null
                        ? previous.knowledgeBaseIds()
                        : command.knowledgeBaseIds(),
                command.enabledToolIds() == null ? previous.enabledToolIds() : command.enabledToolIds(),
                command.skillIds() == null ? previous.skillIds() : command.skillIds(),
                command.knowledgeCollectionIds() == null ? previous.knowledgeCollectionIds() : command.knowledgeCollectionIds());

        String updatedName = command.name() == null ? current.name() : command.name().trim();
        String updatedDescription = command.description() == null
                ? current.description()
                : command.description();
        validateValues(updatedName, updatedInput);
        validateCapabilities(current.tenantId(), updatedInput);
        rejectDuplicateName(current.tenantId(), updatedName, current.id());
        validateReferences(
                current.tenantId(), current.ownerId(), updatedInput.modelPoolId(),
                updatedInput.modelProviderId(), updatedInput.modelId(),
                updatedInput.knowledgeBaseIds());

        validateCollections(current.tenantId(), current.ownerId(), current.ownerId(), updatedInput.knowledgeCollectionIds());
        validateCollections(current.tenantId(), command.ownerId(), current.ownerId(), updatedInput.knowledgeCollectionIds().stream()
                .filter(id -> !previous.knowledgeCollectionIds().contains(id)).toList());
        String configHash = configurationFactory.hash(updatedInput);
        AgentConfigurationProposal proposal = new AgentConfigurationProposal(
                updatedName, updatedDescription, updatedInput.systemPrompt(),
                updatedInput.modelPoolId(), updatedInput.modelProviderId(), updatedInput.modelId(),
                updatedInput.temperature(), previousConfiguration.maxContextTokens(),
                updatedInput.maxOutputTokens(), updatedInput.maxTurns(), updatedInput.permissionMode(),
                updatedInput.memoryEnabled(), updatedInput.ragEnabled(), updatedInput.networkEnabled(),
                updatedInput.knowledgeBaseIds(), updatedInput.enabledToolIds(), updatedInput.skillIds(),
                configHash, updatedInput.knowledgeCollectionIds());
        return new PreparedAgentChange(current, previousConfiguration, proposal);
    }

    Optional<AgentDefinitionView> applyPreparedChange(
            String tenantId,
            String actorId,
            String agentId,
            long expectedAgentRevision,
            String expectedConfigHash,
            AgentConfigurationProposal proposal) {
        AgentDefinition current = agentRepository.findByIdForUpdate(agentId)
                .orElseThrow(() -> new BusinessException(
                        "Agent not found: " + agentId, HttpStatus.NOT_FOUND));
        requireTenant(current, tenantId);
        AgentCurrentConfiguration configuration = currentConfigurations.find(
                current.tenantId(), current.ownerId(), current.id())
                .orElseThrow(() -> new BusinessException(
                        "Agent current configuration is missing", HttpStatus.CONFLICT,
                        "AGENT_CURRENT_CONFIGURATION_MISSING"));
        if (current.revision() != expectedAgentRevision
                || !configuration.configHash().equals(expectedConfigHash)) {
            return Optional.empty();
        }
        ConfigurationInput input = input(proposal);
        validateValues(proposal.name(), input);
        validateCapabilities(current.tenantId(), input);
        rejectDuplicateName(current.tenantId(), proposal.name(), current.id());
        validateReferences(current.tenantId(), current.ownerId(), proposal.modelPoolId(),
                proposal.modelProviderId(), proposal.modelId(), proposal.knowledgeBaseIds());
        validateCollections(current.tenantId(), current.ownerId(), current.ownerId(), proposal.knowledgeCollectionIds());
        if (!configurationFactory.hash(input).equals(proposal.configHash())) {
            throw new BusinessException("Agent proposal hash mismatch", HttpStatus.CONFLICT,
                    "AGENT_CONFIGURATION_PROPOSAL_HASH_MISMATCH");
        }
        return Optional.of(applyPrepared(
                new PreparedAgentChange(current, configuration, proposal), actorId));
    }

    private AgentDefinitionView applyPrepared(PreparedAgentChange prepared, String actorId) {
        AgentDefinition current = prepared.definition();
        AgentConfigurationProposal proposal = prepared.proposal();

        AgentDefinition updated = current.updateMetadata(
                proposal.name(), proposal.description(), timeProvider.now());
        agentRepository.save(updated);
        AgentCurrentConfiguration configuration = persistCurrentConfiguration(
                updated, input(proposal), proposal.configHash(), actorId);
        return toView(updated, configuration);
    }

    private AgentCurrentConfiguration persistCurrentConfiguration(
            AgentDefinition definition,
            ConfigurationInput input,
            String configHash,
            String updatedBy) {
        Optional<AgentCurrentConfiguration> existing = currentConfigurations.find(
                definition.tenantId(), definition.ownerId(), definition.id());
        List<AgentCurrentConfiguration.McpBinding> bindings = existing
                .map(AgentCurrentConfiguration::mcpBindings).orElseGet(List::of);
        AgentCurrentConfiguration updated = currentConfiguration(
                definition, input, configHash, bindings,
                updatedBy, timeProvider.now());
        if (existing.isEmpty()) {
            currentConfigurations.insert(updated);
            return updated;
        }
        return currentConfigurations.update(updated, existing.orElseThrow().agentRevision())
                .orElseThrow(() -> new BusinessException(
                        "Agent current configuration revision conflict",
                        HttpStatus.CONFLICT,
                        "AGENT_CURRENT_CONFIGURATION_REVISION_CONFLICT"));
    }

    private static ConfigurationInput input(AgentConfigurationProposal proposal) {
        return new ConfigurationInput(
                proposal.systemPrompt(), proposal.modelPoolId(), proposal.modelProviderId(),
                proposal.modelId(), proposal.temperature(), proposal.maxOutputTokens(),
                proposal.maxTurns(), proposal.permissionMode(), proposal.memoryEnabled(),
                proposal.ragEnabled(), proposal.networkEnabled(), proposal.knowledgeBaseIds(),
                proposal.enabledToolIds(), proposal.skillIds(), proposal.knowledgeCollectionIds());
    }

    record PreparedAgentChange(
            AgentDefinition definition,
            AgentCurrentConfiguration previousConfiguration,
            AgentConfigurationProposal proposal) {
    }

    private static AgentCurrentConfiguration currentConfiguration(
            AgentDefinition definition,
            ConfigurationInput input,
            String configHash,
            List<AgentCurrentConfiguration.McpBinding> mcpBindings,
            String updatedBy,
            Instant updatedAt) {
        return new AgentCurrentConfiguration(
                definition.id(), definition.ownerId(), definition.tenantId(), definition.revision(),
                configHash, input.modelPoolId(), input.modelProviderId(), input.modelId(),
                input.systemPrompt(), input.temperature(),
                AgentConfigurationFactory.DEFAULT_MAX_CONTEXT_TOKENS,
                input.maxOutputTokens(), input.maxTurns(), input.memoryEnabled(), input.ragEnabled(),
                input.networkEnabled(), input.knowledgeBaseIds(), input.enabledToolIds(),
                input.skillIds(), input.permissionMode(), mcpBindings, updatedBy, updatedAt, input.knowledgeCollectionIds());
    }

    @Override
    @Transactional
    public void archive(String ownerId, String agentId) {
        archive(null, ownerId, agentId);
    }

    @Override
    @Transactional
    public void archive(String tenantId, String ownerId, String agentId) {
        AgentDefinition current = agentRepository.findByIdForUpdate(agentId)
                .orElseThrow(() -> new BusinessException("Agent not found", HttpStatus.NOT_FOUND));
        requireOwner(current, tenantId, ownerId);
        agentRepository.save(current.archive(timeProvider.now()));
    }

    @Override
    public AgentRuntimeConfigurationView runtimeConfiguration(
            String tenantId,
            String ownerId,
            String agentId) {
        AgentDefinition definition = requireOwnedAgent(tenantId, ownerId, agentId);
        AgentCurrentConfiguration configuration = currentConfigurations.find(
                tenantId, ownerId, agentId).orElseThrow(() -> new BusinessException(
                "Agent current configuration is missing", HttpStatus.CONFLICT,
                "AGENT_CURRENT_CONFIGURATION_MISSING"));
        validateReferences(
                definition.tenantId(), definition.ownerId(), configuration.modelPoolId(),
                configuration.modelProviderId(), configuration.modelId(),
                configuration.knowledgeBaseIds());
        return new AgentRuntimeConfigurationView(
                definition.id(), definition.ownerId(), definition.tenantId(),
                configuration.modelPoolId(), configuration.modelProviderId(), configuration.modelId(),
                configuration.systemPrompt(), configuration.temperature(),
                configuration.maxOutputTokens(), configuration.maxTurns(),
                configuration.memoryEnabled(), configuration.ragEnabled(),
                configuration.knowledgeBaseIds(), configuration.enabledToolIds(),
                configuration.skillIds(), configuration.permissionMode(),
                configuration.networkEnabled(), configuration.agentRevision(), configuration.knowledgeCollectionIds());
    }

    @Override
    public boolean isOwner(String agentId, String principalId) {
        return agentRepository.findById(agentId)
                .filter(definition -> principalId.equals(definition.ownerId()))
                .isPresent();
    }

    private AgentDefinitionView toView(AgentDefinition definition) {
        AgentCurrentConfiguration configuration = currentConfigurations.find(
                definition.tenantId(), definition.ownerId(), definition.id())
                .orElseThrow(() -> new BusinessException(
                        "Agent current configuration is missing", HttpStatus.CONFLICT,
                        "AGENT_CURRENT_CONFIGURATION_MISSING"));
        return toView(definition, configuration);
    }

    private List<AgentDefinitionView> toViews(List<AgentDefinition> definitions) {
        if (definitions.isEmpty()) return List.of();
        Map<String, AgentCurrentConfiguration> configurations = definitions.stream()
                .collect(java.util.stream.Collectors.groupingBy(AgentDefinition::tenantId))
                .entrySet().stream()
                .flatMap(entry -> currentConfigurations.findByTenantAndAgentIds(
                        entry.getKey(), entry.getValue().stream().map(AgentDefinition::id).toList())
                        .stream())
                .collect(java.util.stream.Collectors.toMap(
                        AgentCurrentConfiguration::agentId, Function.identity()));
        return definitions.stream().map(definition -> {
            AgentCurrentConfiguration configuration = configurations.get(definition.id());
            if (configuration == null
                    || !definition.tenantId().equals(configuration.tenantId())
                    || !definition.ownerId().equals(configuration.ownerUserId())) {
                throw new BusinessException(
                        "Agent current configuration is missing", HttpStatus.CONFLICT,
                        "AGENT_CURRENT_CONFIGURATION_MISSING");
            }
            return toView(definition, configuration);
        }).toList();
    }

    private static AgentDefinitionView toView(
            AgentDefinition definition,
            AgentCurrentConfiguration configuration) {
        return new AgentDefinitionView(
                definition.id(), definition.ownerId(), definition.tenantId(),
                definition.name(), definition.description(), definition.status(),
                configuration.systemPrompt(), configuration.modelPoolId(),
                configuration.modelProviderId(), configuration.modelId(),
                configuration.temperature(), configuration.maxOutputTokens(),
                configuration.maxTurns(), configuration.permissionMode(),
                configuration.memoryEnabled(), configuration.ragEnabled(),
                configuration.networkEnabled(), configuration.knowledgeBaseIds(),
                configuration.enabledToolIds(), configuration.skillIds(),
                definition.revision(), definition.createdAt(), definition.updatedAt(),
                definition.archivedAt(), configuration.knowledgeCollectionIds());
    }

    private void validateCollections(String tenantId, String actorId, String agentOwnerId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        if (knowledgeAccess == null) throw new BusinessException("Knowledge collection authorization is unavailable",
                HttpStatus.SERVICE_UNAVAILABLE, "KNOWLEDGE_ACCESS_UNAVAILABLE");
        for (String id : ids) {
            knowledgeAccess.authorize(new com.spaceagent.platform.knowledge.api.KnowledgeBaseApplicationApi.Actor(actorId, tenantId),
                    id, com.spaceagent.platform.knowledge.domain.KnowledgeBase.Permission.READ);
            if (!actorId.equals(agentOwnerId)) knowledgeAccess.authorize(
                    new com.spaceagent.platform.knowledge.api.KnowledgeBaseApplicationApi.Actor(agentOwnerId, tenantId),
                    id, com.spaceagent.platform.knowledge.domain.KnowledgeBase.Permission.READ);
        }
    }

    private AgentDefinition requireOwnedAgent(String tenantId, String ownerId, String agentId) {
        AgentDefinition definition = agentRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException("Agent not found", HttpStatus.NOT_FOUND));
        if (!ownerId.equals(definition.ownerId()) || !tenantId.equals(definition.tenantId())) {
            throw new BusinessException("Agent not found", HttpStatus.NOT_FOUND);
        }
        return definition;
    }

    private static void requireOwner(
            AgentDefinition definition,
            String tenantId,
            String ownerId) {
        if (!ownerId.equals(definition.ownerId())
                || (tenantId != null && !tenantId.equals(definition.tenantId()))) {
            throw new BusinessException("Agent access denied", HttpStatus.FORBIDDEN);
        }
    }

    private static void requireTenant(AgentDefinition definition, String tenantId) {
        if (tenantId == null || !tenantId.equals(definition.tenantId())) {
            throw new BusinessException("Agent not found", HttpStatus.NOT_FOUND);
        }
    }

    private void rejectDuplicateName(String tenantId, String name, String currentId) {
        agentRepository.findByTenantAndName(tenantId, name.trim())
                .filter(existing -> currentId == null || !currentId.equals(existing.id()))
                .ifPresent(existing -> {
                    throw new BusinessException(
                            "Agent name already exists in tenant",
                            HttpStatus.CONFLICT,
                            "AGENT_NAME_CONFLICT");
                });
    }

    private void validateReferences(
            String tenantId,
            String ownerId,
            String modelPoolId,
            String providerId,
            String modelId,
            List<String> knowledgeIds) {
        boolean hasPool = modelPoolId != null && !modelPoolId.isBlank();
        boolean hasProvider = providerId != null && !providerId.isBlank();
        boolean hasModel = modelId != null && !modelId.isBlank();
        if (hasPool && (hasProvider || hasModel)) {
            throw new BusinessException(
                    "modelPoolId cannot coexist with modelProviderId/modelId",
                    HttpStatus.BAD_REQUEST,
                    "MODEL_BINDING_CONFLICT");
        }
        if (hasProvider != hasModel) {
            throw new BusinessException(
                    "modelProviderId and modelId must be configured together",
                    HttpStatus.BAD_REQUEST,
                    "MODEL_BINDING_INCOMPLETE");
        }
        if (hasProvider && inferenceApi != null) {
            inferenceApi.validateSelection(tenantId, providerId, modelId);
        }
        if (hasPool) {
            if (modelPoolApi == null) {
                throw new BusinessException(
                        "ModelPool validation is unavailable",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "MODEL_POOL_VALIDATION_UNAVAILABLE");
            }
            modelPoolApi.resolvePool(tenantId, ownerId, modelPoolId);
        }
        if (knowledgeOwnershipPort != null) {
            knowledgeIds.stream()
                    .filter(id -> !knowledgeOwnershipPort.canAccess(id, ownerId))
                    .findFirst()
                    .ifPresent(id -> {
                        throw new BusinessException(
                                "Knowledge reference not found: " + id,
                                HttpStatus.NOT_FOUND,
                                "KNOWLEDGE_REFERENCE_NOT_FOUND");
                    });
        }
    }

    private void validateValues(String name, ConfigurationInput input) {
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw new BusinessException(
                    "Agent name must be between 1 and 100 characters", HttpStatus.BAD_REQUEST);
        }
        if (input.temperature() < 0.0 || input.temperature() > 2.0) {
            throw new BusinessException(
                    "Agent temperature must be between 0.0 and 2.0", HttpStatus.BAD_REQUEST);
        }
        if (input.maxOutputTokens() < 1 || input.maxOutputTokens() > 32768) {
            throw new BusinessException(
                    "Agent maxTokens must be between 1 and 32768", HttpStatus.BAD_REQUEST);
        }
        if (input.maxTurns() < 1 || input.maxTurns() > 100) {
            throw new BusinessException(
                    "Agent maxTurns must be between 1 and 100", HttpStatus.BAD_REQUEST);
        }
        if (!List.of("private", "auto", "ask", "deny").contains(input.permissionMode())) {
            throw new BusinessException(
                    "Unsupported Agent permission mode", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateCapabilities(String tenantId, ConfigurationInput input) {
        if (capabilityCatalogApi == null) {
            return;
        }
        if (input.enabledToolIds().contains("knowledge_search")
                && (!input.ragEnabled() || input.knowledgeBaseIds().isEmpty())) {
            throw new BusinessException(
                    "Knowledge Search requires enabled retrieval and a Knowledge binding",
                    HttpStatus.BAD_REQUEST,
                    "AGENT_KNOWLEDGE_TOOL_REQUIRES_BINDING");
        }
        input.enabledToolIds().stream()
                .filter(id -> !capabilityCatalogApi.supportsTool(id))
                .findFirst()
                .ifPresent(id -> {
                    throw new BusinessException(
                            "Runtime Tool is not registered: " + id,
                            HttpStatus.BAD_REQUEST,
                            "AGENT_TOOL_NOT_REGISTERED");
                });
        capabilityCatalogApi.validateSkillBindings(
                tenantId, input.skillIds(), input.enabledToolIds());
    }

    private static String defaultPermission(String permissionMode) {
        return permissionMode == null || permissionMode.isBlank() ? "private" : permissionMode;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ModelSelection updatedSelection(
            UpdateAgentDefinitionCommand command,
            ConfigurationInput previous) {
        boolean poolTouched = command.modelPoolId() != null;
        boolean providerTouched = command.modelProviderId() != null;
        boolean modelTouched = command.modelId() != null;
        if (!poolTouched && !providerTouched && !modelTouched) {
            return new ModelSelection(
                    previous.modelPoolId(), previous.modelProviderId(), previous.modelId());
        }

        String requestedPool = blankToNull(command.modelPoolId());
        if (requestedPool != null) {
            return new ModelSelection(
                    requestedPool,
                    blankToNull(command.modelProviderId()),
                    blankToNull(command.modelId()));
        }

        if (poolTouched || previous.modelPoolId() != null) {
            return new ModelSelection(
                    null,
                    blankToNull(command.modelProviderId()),
                    blankToNull(command.modelId()));
        }
        return new ModelSelection(
                null,
                providerTouched
                        ? blankToNull(command.modelProviderId()) : previous.modelProviderId(),
                modelTouched ? blankToNull(command.modelId()) : previous.modelId());
    }

    private record ModelSelection(
            String modelPoolId,
            String modelProviderId,
            String modelId) {
    }
}
