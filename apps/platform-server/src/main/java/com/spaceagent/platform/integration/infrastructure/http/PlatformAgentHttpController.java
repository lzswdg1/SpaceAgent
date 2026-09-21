package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.api.AgentApiKeyApplicationApi;
import com.spaceagent.platform.agent.api.AgentApiKeyView;
import com.spaceagent.platform.agent.api.CreateAgentApiKeyCommand;
import com.spaceagent.platform.agent.api.CreateAgentDefinitionCommand;
import com.spaceagent.platform.agent.api.CreatedAgentApiKeyView;
import com.spaceagent.platform.agent.api.RevokeAgentApiKeyCommand;
import com.spaceagent.platform.agent.api.UpdateAgentDefinitionCommand;
import com.spaceagent.platform.agent.api.AgentConfigurationChangeApplicationApi;
import com.spaceagent.platform.agent.domain.AgentApiKeyScope;
import com.spaceagent.platform.integration.application.OrganizationAgentConfigurationCoordinator;
import com.spaceagent.platform.inference.api.AddProviderModelCommand;
import com.spaceagent.platform.inference.api.CreateModelProviderCommand;
import com.spaceagent.platform.inference.api.InferenceApplicationApi;
import com.spaceagent.platform.inference.api.ModelProviderView;
import com.spaceagent.platform.inference.api.ProviderModelView;
import com.spaceagent.platform.inference.api.ProviderConnectionApplicationApi;
import com.spaceagent.platform.inference.api.ProviderConnectionTestView;
import com.spaceagent.platform.inference.api.TestProviderConnectionCommand;
import com.spaceagent.platform.inference.api.UpdateModelProviderCommand;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Public agent/config and model-provider HTTP adapter.
 *
 * <p>All persistence and validation is delegated to the public agent and inference
 * application APIs. This controller adapts the canonical public HTTP contract.
 */
@RestController
@RequestMapping("/api/v1")
public class PlatformAgentHttpController {

    private final AgentApplicationApi agentApi;
    private final AgentApiKeyApplicationApi apiKeyApi;
    private final InferenceApplicationApi inferenceApi;
    private final ProviderConnectionApplicationApi providerConnectionApi;
    private final OrganizationAgentConfigurationCoordinator organizationAgents;

    public PlatformAgentHttpController(
            AgentApplicationApi agentApi,
            AgentApiKeyApplicationApi apiKeyApi,
            InferenceApplicationApi inferenceApi,
            ProviderConnectionApplicationApi providerConnectionApi,
            OrganizationAgentConfigurationCoordinator organizationAgents) {
        this.agentApi = agentApi;
        this.apiKeyApi = apiKeyApi;
        this.inferenceApi = inferenceApi;
        this.providerConnectionApi = providerConnectionApi;
        this.organizationAgents = organizationAgents;
    }

    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentResponse> createAgent(
            @Valid @RequestBody AgentWriteRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        if (request.name() == null || request.name().isBlank()) {
            throw new BusinessException("Agent name is required", HttpStatus.BAD_REQUEST);
        }
        String ownerId = PlatformHttpSupport.userId(authentication);
        String tenantId = PlatformHttpSupport.tenantId(authentication);
        AgentDefinitionView created = agentApi.create(new CreateAgentDefinitionCommand(
                ownerId,
                tenantId,
                request.name(),
                request.description(),
                request.systemPrompt(),
                request.modelPoolId(),
                request.modelProviderId(),
                request.modelId(),
                request.temperature(),
                request.maxTokens(),
                request.maxTurns(),
                request.permissionMode(),
                request.memoryEnabled(),
                request.ragEnabled(),
                request.networkEnabled(),
                request.knowledgeBaseIds(),
                request.enabledToolIds(),
                request.skillIds(), request.knowledgeCollectionIds()));
        return ApiResponse.ok(AgentResponse.from(created));
    }

    @GetMapping("/agents")
    public ApiResponse<?> listAgents(Authentication authentication,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="100") int limit,
            @RequestParam(defaultValue="false") boolean summary) {
        var rows = agentApi.page(PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication), offset, limit);
        return ApiResponse.ok(summary ? rows.stream().map(AgentSelectionResponse::from).toList()
                : rows.stream().map(AgentResponse::from).toList());
    }

    @GetMapping("/agents/{agentId}")
    public ApiResponse<AgentResponse> getAgent(
            @PathVariable String agentId,
            Authentication authentication) {
        return ApiResponse.ok(AgentResponse.from(organizationAgents.getOrganizationAgent(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), agentId).agent()));
    }

    @GetMapping("/agents/organization")
    public ApiResponse<?> listOrganizationAgents(
            Authentication authentication, @RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="100") int limit, @RequestParam(defaultValue="false") boolean summary) {
        var rows = organizationAgents.pageOrganizationAgents(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), offset, limit);
        return ApiResponse.ok(summary ? rows.stream().map(value -> new OrganizationAgentSelectionResponse(
                AgentSelectionResponse.from(value.agent()), value.writeMode().name())).toList() : rows.stream()
                .map(value -> new OrganizationAgentResponse(
                        AgentResponse.from(value.agent()), value.writeMode().name()))
                .toList());
    }

    @PutMapping("/agents/{agentId}")
    public ResponseEntity<ApiResponse<?>> updateAgent(
            @PathVariable String agentId,
            @Valid @RequestBody AgentWriteRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        var result = organizationAgents.save(new UpdateAgentDefinitionCommand(
                PlatformHttpSupport.userId(authentication),
                PlatformHttpSupport.tenantId(authentication),
                agentId,
                request.name(),
                request.description(),
                request.systemPrompt(),
                request.modelPoolId(),
                request.modelProviderId(),
                request.modelId(),
                request.temperature(),
                request.maxTokens(),
                request.maxTurns(),
                request.permissionMode(),
                request.memoryEnabled(),
                request.ragEnabled(),
                request.networkEnabled(),
                request.knowledgeBaseIds(),
                request.enabledToolIds(), request.skillIds(), request.knowledgeCollectionIds()));
        if (result.outcome()
                == OrganizationAgentConfigurationCoordinator.SaveOutcome.APPLIED) {
            return ResponseEntity.ok(ApiResponse.ok(AgentResponse.from(result.agent())));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                new AgentSaveResponse(
                        result.outcome().name(), AgentResponse.from(result.agent()),
                        result.changeRequest())));
    }

    @PatchMapping("/agents/{agentId}")
    public ResponseEntity<ApiResponse<?>> patchAgent(
            @PathVariable String agentId,
            @Valid @RequestBody AgentWriteRequest request,
            Authentication authentication) {
        return updateAgent(agentId, request, authentication);
    }

    @GetMapping("/agents/{agentId}/configuration-changes")
    public ApiResponse<List<AgentConfigurationChangeApplicationApi.AgentConfigurationChangeRequestView>>
            listConfigurationChanges(
                    @PathVariable String agentId,
                    @RequestParam(defaultValue = "0") int offset,
                    @RequestParam(defaultValue = "50") int limit,
                    Authentication authentication) {
        return ApiResponse.ok(organizationAgents.listChanges(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), agentId, offset, limit));
    }

    @GetMapping("/agents/{agentId}/configuration-changes/{requestId}")
    public ApiResponse<AgentConfigurationChangeApplicationApi.AgentConfigurationChangeRequestView>
            getConfigurationChange(
                    @PathVariable String agentId,
                    @PathVariable String requestId,
                    Authentication authentication) {
        return ApiResponse.ok(organizationAgents.getChange(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), agentId, requestId));
    }

    @PostMapping("/agents/{agentId}/configuration-changes/{requestId}/decision")
    public ApiResponse<AgentConfigurationChangeApplicationApi.AgentConfigurationChangeRequestView>
            decideConfigurationChange(
                    @PathVariable String agentId,
                    @PathVariable String requestId,
                    @Valid @RequestBody AgentConfigurationChangeDecisionRequest request,
                    Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(organizationAgents.decide(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), agentId, requestId,
                request.expectedRevision(), request.decision(), request.note()));
    }

    @DeleteMapping("/agents/{agentId}")
    public ApiResponse<Void> deleteAgent(
            @PathVariable String agentId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        requireOwnedAgent(agentId, authentication);
        agentApi.archive(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                agentId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/agents/{agentId}/knowledge-bindings")
    public ApiResponse<List<String>> knowledgeBindings(
            @PathVariable String agentId,
            Authentication authentication) {
        return ApiResponse.ok(requireOwnedAgent(agentId, authentication).knowledgeBaseIds());
    }

    @PutMapping("/agents/{agentId}/knowledge-bindings")
    public ApiResponse<AgentResponse> replaceKnowledgeBindings(
            @PathVariable String agentId,
            @Valid @RequestBody KnowledgeBindingsRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        requireOwnedAgent(agentId, authentication);
        AgentDefinitionView updated = agentApi.update(new UpdateAgentDefinitionCommand(
                PlatformHttpSupport.userId(authentication),
                PlatformHttpSupport.tenantId(authentication),
                agentId,
                null, null, null, null, null, null, null, null, null,
                null, null, null, request.knowledgeBaseIds(), null, null));
        return ApiResponse.ok(AgentResponse.from(updated));
    }

    @PostMapping("/agents/{agentId}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedAgentApiKeyView> createAgentApiKey(
            @PathVariable String agentId,
            @Valid @RequestBody CreateAgentApiKeyRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        return ApiResponse.ok(apiKeyApi.create(new CreateAgentApiKeyCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                agentId,
                request.name(),
                request.scopes(),
                request.expiresAt())));
    }

    @GetMapping("/agents/{agentId}/keys")
    public ApiResponse<List<AgentApiKeyView>> listAgentApiKeys(
            @PathVariable String agentId,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        return ApiResponse.ok(apiKeyApi.list(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                agentId));
    }

    @DeleteMapping("/agents/{agentId}/keys/{keyId}")
    public ApiResponse<Void> revokeAgentApiKey(
            @PathVariable String agentId,
            @PathVariable String keyId,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        apiKeyApi.revoke(new RevokeAgentApiKeyCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                agentId,
                keyId));
        return ApiResponse.ok(null);
    }

    @GetMapping("/model-providers")
    public ApiResponse<List<ModelProviderResponse>> listProviders(Authentication authentication) {
        return ApiResponse.ok(inferenceApi.listProvidersByTenant(PlatformHttpSupport.tenantId(authentication)).stream()
                .map(ModelProviderResponse::from)
                .toList());
    }

    @GetMapping("/model-providers/{providerId}")
    public ApiResponse<ModelProviderResponse> getProvider(
            @PathVariable String providerId,
            Authentication authentication) {
        return ApiResponse.ok(ModelProviderResponse.from(requireOwnedProvider(providerId, authentication)));
    }

    @PostMapping("/model-providers")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ModelProviderResponse> createProvider(
            @Valid @RequestBody ProviderWriteRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        ModelProviderView created = inferenceApi.createProvider(new CreateModelProviderCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                request.name(),
                request.type(),
                request.baseUrl(),
                request.apiKey(),
                request.authType(),
                request.enabled() == null || request.enabled(),
                request.isDefault() == null || request.isDefault(),
                request.models() == null ? List.of() : request.models().stream()
                        .map(model -> new CreateModelProviderCommand.ProviderModelDraft(
                                model.modelId(), model.displayName(), model.maxContextTokens()))
                        .toList()));
        return ApiResponse.ok(ModelProviderResponse.from(created));
    }

    @PutMapping("/model-providers/{providerId}")
    public ApiResponse<ModelProviderResponse> updateProvider(
            @PathVariable String providerId,
            @Valid @RequestBody ProviderWriteRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        ModelProviderView updated = inferenceApi.updateProvider(new UpdateModelProviderCommand(
                PlatformHttpSupport.tenantId(authentication),
                providerId,
                request.name(),
                request.type(),
                request.baseUrl(),
                request.apiKey(),
                request.authType(),
                request.enabled(),
                request.isDefault(),
                request.models() == null ? null : request.models().stream()
                        .map(model -> new CreateModelProviderCommand.ProviderModelDraft(
                                model.modelId(), model.displayName(), model.maxContextTokens()))
                        .toList()));
        return ApiResponse.ok(ModelProviderResponse.from(updated));
    }

    @PatchMapping("/model-providers/{providerId}")
    public ApiResponse<ModelProviderResponse> patchProvider(
            @PathVariable String providerId,
            @Valid @RequestBody ProviderWriteRequest request,
            Authentication authentication) {
        return updateProvider(providerId, request, authentication);
    }

    @DeleteMapping("/model-providers/{providerId}")
    public ApiResponse<Void> deleteProvider(
            @PathVariable String providerId,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        inferenceApi.deleteProvider(PlatformHttpSupport.tenantId(authentication), providerId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/model-providers/{providerId}/test")
    public ApiResponse<ProviderConnectionTestView> testProvider(
            @PathVariable String providerId,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        requireOwnedProvider(providerId, authentication);
        return ApiResponse.ok(providerConnectionApi.testProvider(
                new TestProviderConnectionCommand(
                        PlatformHttpSupport.tenantId(authentication), providerId)));
    }

    @PostMapping("/model-providers/{providerId}/models/{modelId}/test")
    public ApiResponse<com.spaceagent.platform.inference.api.ProviderModelTestView> testProviderModel(
            @PathVariable String providerId,
            @PathVariable String modelId,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        requireOwnedProvider(providerId, authentication);
        return ApiResponse.ok(providerConnectionApi.testModel(
                new com.spaceagent.platform.inference.api.TestProviderModelCommand(
                        PlatformHttpSupport.tenantId(authentication), providerId, modelId)));
    }

    @PostMapping("/model-providers/{providerId}/models/test")
    public ApiResponse<com.spaceagent.platform.inference.api.ProviderModelTestView> testProviderModel(
            @PathVariable String providerId,
            @Valid @RequestBody ProviderModelTestRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        requireOwnedProvider(providerId, authentication);
        return ApiResponse.ok(providerConnectionApi.testModel(
                new com.spaceagent.platform.inference.api.TestProviderModelCommand(
                        PlatformHttpSupport.tenantId(authentication), providerId, request.modelId())));
    }

    @GetMapping("/model-providers/{providerId}/models")
    public ApiResponse<List<ModelResponse>> listModels(
            @PathVariable String providerId,
            Authentication authentication) {
        requireOwnedProvider(providerId, authentication);
        return ApiResponse.ok(inferenceApi.listModels(providerId).stream()
                .map(ModelResponse::from)
                .toList());
    }

    @PostMapping("/model-providers/{providerId}/models")
    public ApiResponse<ModelResponse> addModel(
            @PathVariable String providerId,
            @Valid @RequestBody ModelWriteRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        ProviderModelView created = inferenceApi.addModel(new AddProviderModelCommand(
                PlatformHttpSupport.tenantId(authentication),
                providerId,
                request.modelId(),
                request.displayName(),
                request.maxContextTokens(),
                request.isDefault() == null || request.isDefault()));
        return ApiResponse.ok(ModelResponse.from(created));
    }

    @DeleteMapping("/model-providers/{providerId}/models/{modelId}")
    public ApiResponse<Void> deleteModel(
            @PathVariable String providerId,
            @PathVariable String modelId,
            Authentication authentication) {
        PlatformHttpSupport.requireAdmin(authentication);
        inferenceApi.deleteModel(PlatformHttpSupport.tenantId(authentication), providerId, modelId);
        return ApiResponse.ok(null);
    }

    private AgentDefinitionView requireOwnedAgent(String agentId, Authentication authentication) {
        return agentApi.findById(agentId)
                .filter(config -> PlatformHttpSupport.userId(authentication).equals(config.ownerId()))
                .filter(config -> PlatformHttpSupport.tenantId(authentication).equals(config.tenantId()))
                .orElseThrow(() -> new BusinessException("Agent not found", HttpStatus.NOT_FOUND));
    }

    private ModelProviderView requireOwnedProvider(String providerId, Authentication authentication) {
        return inferenceApi.findProvider(providerId)
                .filter(provider -> PlatformHttpSupport.tenantId(authentication).equals(provider.tenantId()))
                .orElseThrow(() -> new BusinessException("Model provider not found", HttpStatus.NOT_FOUND));
    }

    public record AgentWriteRequest(
            @Size(min = 1, max = 100) String name,
            @Size(max = 500) String description,
            @Size(max = 50000) String systemPrompt,
            @Size(max = 36) String modelPoolId,
            @Size(max = 100) String modelProviderId,
            @Size(max = 160) String modelId,
            @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
            @Min(1) @Max(32768) Integer maxTokens,
            @Min(1) @Max(100) Integer maxTurns,
            @Pattern(regexp = "private|auto|ask|deny") String permissionMode,
            Boolean memoryEnabled,
            Boolean ragEnabled,
            Boolean networkEnabled,
            List<@NotBlank @Size(max = 64) String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds,
            @Size(max = 64) List<@NotBlank @Size(max = 36) String> knowledgeCollectionIds) {
    }

    public record KnowledgeBindingsRequest(
            List<@NotBlank @Size(max = 64) String> knowledgeBaseIds) {

        public KnowledgeBindingsRequest {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        }
    }

    public record CreateAgentApiKeyRequest(
            @NotBlank @Size(max = 128) String name,
            Set<AgentApiKeyScope> scopes,
            Instant expiresAt) {
    }

    public record ProviderWriteRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 32) String type,
            @NotBlank @Size(max = 500) String baseUrl,
            @Size(max = 4096) String apiKey,
            @Size(max = 32) String authType,
            Boolean enabled,
            Boolean isDefault,
            List<ModelWriteRequest> models) {
    }

    public record ModelWriteRequest(
            @NotBlank @Size(max = 160) String modelId,
            @Size(max = 160) String displayName,
            @Min(1024) @Max(2000000) Integer maxContextTokens,
            Boolean isDefault) {
    }

    public record ProviderModelTestRequest(
            @NotBlank @Size(max = 160) String modelId) {
    }

    public record AgentResponse(
            String id,
            String ownerId,
            String name,
            String description,
            String status,
            String systemPrompt,
            String modelPoolId,
            String modelProviderId,
            String modelId,
            Double temperature,
            Integer maxTokens,
            Integer maxTurns,
            String permissionMode,
            Boolean memoryEnabled,
            Boolean ragEnabled,
            Boolean networkEnabled,
            List<String> knowledgeBaseIds,
            List<String> enabledToolIds,
            List<String> skillIds,
            Long revision,
            Instant createdAt,
            Instant updatedAt,
            List<String> knowledgeCollectionIds) {

        static AgentResponse from(AgentDefinitionView config) {
            return new AgentResponse(
                    config.id(),
                    config.ownerId(),
                    config.name(),
                    config.description(),
                    config.status().name(),
                    config.systemPrompt(),
                    config.modelPoolId(),
                    config.modelProviderId(),
                    config.modelId(),
                    config.temperature(),
                    config.maxTokens(),
                    config.maxTurns(),
                    config.permissionMode(),
                    config.memoryEnabled(),
                    config.ragEnabled(),
                    config.networkEnabled(),
                    config.knowledgeBaseIds(),
                    config.enabledToolIds(),
                    config.skillIds(),
                    config.revision(),
                    config.createdAt(),
                    config.updatedAt(), config.knowledgeCollectionIds());
        }
    }

    public record OrganizationAgentResponse(AgentResponse agent, String writeMode) {
    }

    public record AgentSelectionResponse(String id, String ownerId, String tenantId, String name, String description,
            String status, String modelPoolId, String modelProviderId, String modelId, int maxTokens, int maxTurns,
            String permissionMode, boolean memoryEnabled, boolean ragEnabled, boolean networkEnabled,
            List<String> knowledgeBaseIds, List<String> enabledToolIds, List<String> skillIds, List<String> knowledgeCollectionIds) {
        static AgentSelectionResponse from(AgentDefinitionView v) {
            return new AgentSelectionResponse(v.id(),v.ownerId(),v.tenantId(),v.name(),v.description(),v.status().name(),
                    v.modelPoolId(),v.modelProviderId(),v.modelId(),v.maxTokens(),v.maxTurns(),v.permissionMode(),
                    v.memoryEnabled(),v.ragEnabled(),v.networkEnabled(),v.knowledgeBaseIds(),v.enabledToolIds(),v.skillIds(),v.knowledgeCollectionIds());
        }
    }
    public record OrganizationAgentSelectionResponse(AgentSelectionResponse agent, String writeMode) {}

    public record AgentSaveResponse(
            String outcome,
            AgentResponse agent,
            AgentConfigurationChangeApplicationApi.AgentConfigurationChangeRequestView changeRequest) {
    }

    public record AgentConfigurationChangeDecisionRequest(
            @Min(1) long expectedRevision,
            @NotNull AgentConfigurationChangeApplicationApi.Decision decision,
            @Size(max = 2_000) String note) {
    }

    public record ModelProviderResponse(
            String id,
            String name,
            String type,
            String baseUrl,
            String apiKey,
            String authType,
            boolean enabled,
            boolean isDefault,
            com.spaceagent.platform.inference.domain.ProviderConnectionStatus connectionStatus,
            Instant lastTestedAt,
            Integer lastTestLatencyMs,
            String lastTestErrorCode,
            Instant createdAt,
            Instant updatedAt) {

        static ModelProviderResponse from(ModelProviderView provider) {
            return new ModelProviderResponse(
                    provider.id(),
                    provider.name(),
                    provider.providerType(),
                    provider.baseUrl(),
                    provider.hasSecret() ? "configured" : "",
                    provider.authType(),
                    provider.enabled(),
                    provider.isDefault(),
                    provider.connectionStatus(),
                    provider.lastTestedAt(),
                    provider.lastTestLatencyMs(),
                    provider.lastTestErrorCode(),
                    provider.createdAt(),
                    provider.updatedAt());
        }
    }

    public record ModelResponse(
            String id,
            String providerId,
            String modelId,
            String displayName,
            boolean isDefault,
            int maxContextTokens,
            Instant createdAt) {

        static ModelResponse from(ProviderModelView model) {
            return new ModelResponse(
                    model.id(),
                    model.providerId(),
                    model.modelId(),
                    model.displayName(),
                    model.isDefault(),
                    model.maxContextTokens(),
                    model.createdAt());
        }
    }
}
