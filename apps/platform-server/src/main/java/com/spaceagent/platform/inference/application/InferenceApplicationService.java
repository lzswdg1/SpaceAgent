package com.spaceagent.platform.inference.application;

import com.spaceagent.platform.inference.api.AddProviderModelCommand;
import com.spaceagent.platform.inference.api.CreateModelProviderCommand;
import com.spaceagent.platform.inference.api.InferenceApplicationApi;
import com.spaceagent.platform.inference.api.InferenceOwnershipPort;
import com.spaceagent.platform.inference.api.ModelProviderView;
import com.spaceagent.platform.inference.api.ProviderModelView;
import com.spaceagent.platform.inference.api.UpdateModelProviderCommand;
import com.spaceagent.platform.inference.domain.InferenceProviderRepository;
import com.spaceagent.platform.inference.domain.ModelProvider;
import com.spaceagent.platform.inference.domain.ModelPoolRepository;
import com.spaceagent.platform.inference.domain.ModelProviderEndpointPolicy;
import com.spaceagent.platform.inference.domain.ModelProviderSecretCipher;
import com.spaceagent.platform.inference.domain.ProviderModel;
import com.spaceagent.platform.inference.domain.ProviderHealthProbeRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * In-module application coordinator for model providers. Agent configuration
 * may reference providers only through this public API; provider execution
 * remains owned by the inference module.
 */
@Service
public class InferenceApplicationService implements InferenceApplicationApi, InferenceOwnershipPort {

    public static final int DEFAULT_MODEL_CONTEXT_TOKENS = 200_000;

    private final InferenceProviderRepository repository;
    private final ModelPoolRepository poolRepository;
    private final ModelProviderSecretCipher secretCipher;
    private final ModelProviderEndpointPolicy endpointPolicy;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final ProviderHealthProbeRepository healthProbes;

    public InferenceApplicationService(
            InferenceProviderRepository repository,
            ModelPoolRepository poolRepository,
            ModelProviderSecretCipher secretCipher,
            ModelProviderEndpointPolicy endpointPolicy,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            ProviderHealthProbeRepository healthProbes) {
        this.repository = repository;
        this.poolRepository = poolRepository;
        this.secretCipher = secretCipher;
        this.endpointPolicy = endpointPolicy;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.healthProbes = healthProbes;
    }

    @Override
    public ModelProviderView createProvider(CreateModelProviderCommand command) {
        Instant now = timeProvider.now();
        ModelProvider provider = new ModelProvider(
                idGenerator.nextId(),
                command.tenantId(),
                command.ownerId(),
                command.name(),
                command.providerType(),
                endpointPolicy.validateAndNormalize(command.baseUrl()),
                secretCipher.encrypt(command.apiKey()),
                command.authType() == null || command.authType().isBlank() ? "bearer" : command.authType(),
                command.enabled(),
                command.isDefault(),
                now,
                now);
        if (command.isDefault()) {
            clearDefault(provider.tenantId());
        }
        repository.saveProvider(provider);
        healthProbes.synchronize(provider, now);
        if (command.models() != null) {
            for (int index = 0; index < command.models().size(); index++) {
                CreateModelProviderCommand.ProviderModelDraft draft = command.models().get(index);
                saveModel(provider, draft.modelId(), draft.displayName(),
                        draft.maxContextTokens() == null ? DEFAULT_MODEL_CONTEXT_TOKENS : draft.maxContextTokens(),
                        index == 0, now);
            }
        }
        return toView(provider);
    }

    @Override
    public ModelProviderView updateProvider(UpdateModelProviderCommand command) {
        ModelProvider current = repository.findProviderByTenantAndId(command.tenantId(), command.providerId())
                .orElseThrow(() -> new BusinessException("Model provider not found", HttpStatus.NOT_FOUND));
        boolean isDefault = command.isDefault() == null ? current.isDefault() : command.isDefault();
        if (isDefault && !current.isDefault()) {
            clearDefault(current.tenantId());
        }
        String providerType = command.providerType() == null
                ? current.providerType() : command.providerType();
        String baseUrl = command.baseUrl() == null
                ? current.baseUrl() : endpointPolicy.validateAndNormalize(command.baseUrl());
        String encryptedApiKey = isSecretProvided(command.apiKey())
                ? secretCipher.encrypt(command.apiKey()) : current.encryptedApiKey();
        String authType = command.authType() == null ? current.authType() : command.authType();
        boolean enabled = command.enabled() == null ? current.enabled() : command.enabled();
        boolean connectionSensitiveChange = !providerType.equals(current.providerType())
                || !baseUrl.equals(current.baseUrl())
                || !encryptedApiKey.equals(current.encryptedApiKey())
                || !authType.equals(current.authType())
                || (enabled && !current.enabled());
        ModelProvider updated = new ModelProvider(
                current.id(),
                current.tenantId(),
                current.ownerId(),
                command.name() == null ? current.name() : command.name(),
                providerType,
                baseUrl,
                encryptedApiKey,
                authType,
                enabled,
                isDefault,
                current.createdAt(),
                timeProvider.now(),
                !enabled
                        ? com.spaceagent.platform.inference.domain.ProviderConnectionStatus.DISABLED
                        : connectionSensitiveChange
                        ? com.spaceagent.platform.inference.domain.ProviderConnectionStatus.UNTESTED
                        : current.connectionStatus(),
                connectionSensitiveChange || !enabled ? null : current.lastTestedAt(),
                connectionSensitiveChange || !enabled ? null : current.lastTestLatencyMs(),
                connectionSensitiveChange || !enabled ? null : current.lastTestErrorCode());
        repository.saveProvider(updated);
        healthProbes.synchronize(updated, updated.updatedAt());
        if (command.models() != null) {
            if (repository.findModelsByProviderId(updated.id()).stream()
                    .anyMatch(model -> poolRepository.isProviderModelReferenced(model.id()))) {
                throw new BusinessException(
                        "Provider models are referenced by ModelPool",
                        HttpStatus.CONFLICT,
                        "MODEL_POOL_PROVIDER_MODEL_REFERENCED");
            }
            repository.deleteModelsByProviderId(updated.id());
            for (int index = 0; index < command.models().size(); index++) {
                CreateModelProviderCommand.ProviderModelDraft draft = command.models().get(index);
                saveModel(updated, draft.modelId(), draft.displayName(),
                        draft.maxContextTokens() == null ? DEFAULT_MODEL_CONTEXT_TOKENS : draft.maxContextTokens(),
                        index == 0, timeProvider.now());
            }
        }
        return toView(updated);
    }

    @Override
    public Optional<ModelProviderView> findProvider(String providerId) {
        return repository.findProviderById(providerId).map(InferenceApplicationService::toView);
    }

    @Override
    public List<ModelProviderView> listProvidersByTenant(String tenantId) {
        return repository.findProvidersByTenantId(tenantId).stream()
                .map(InferenceApplicationService::toView)
                .toList();
    }

    @Override
    public ProviderModelView addModel(AddProviderModelCommand command) {
        ModelProvider provider = repository.findProviderByTenantAndId(command.tenantId(), command.providerId())
                .orElseThrow(() -> new BusinessException("Model provider not found", HttpStatus.NOT_FOUND));
        if (command.isDefault()) {
            repository.findModelsByProviderId(provider.id()).stream()
                    .filter(ProviderModel::isDefault)
                    .forEach(model -> repository.saveModel(new ProviderModel(
                            model.id(), model.providerId(), model.modelId(), model.displayName(),
                            model.maxContextTokens(), false, model.createdAt())));
        }
        ProviderModel model = saveModel(
                provider,
                command.modelId(),
                command.displayName(),
                command.maxContextTokens() == null ? DEFAULT_MODEL_CONTEXT_TOKENS : command.maxContextTokens(),
                command.isDefault(),
                timeProvider.now());
        return toModelView(model);
    }

    @Override
    public List<ProviderModelView> listModels(String providerId) {
        ModelProvider provider = repository.findProviderById(providerId)
                .orElseThrow(() -> new BusinessException("Model provider not found", HttpStatus.NOT_FOUND));
        return repository.findModelsByProviderId(provider.id()).stream()
                .map(InferenceApplicationService::toModelView)
                .toList();
    }

    @Override
    public void deleteModel(String tenantId, String providerId, String modelId) {
        ModelProvider provider = repository.findProviderByTenantAndId(tenantId, providerId)
                .orElseThrow(() -> new BusinessException("Model provider not found", HttpStatus.NOT_FOUND));
        repository.findModelsByProviderId(provider.id()).stream()
                .filter(model -> modelId.equals(model.modelId()))
                .filter(model -> poolRepository.isProviderModelReferenced(model.id()))
                .findFirst()
                .ifPresent(model -> {
                    throw new BusinessException(
                            "Provider model is referenced by ModelPool",
                            HttpStatus.CONFLICT,
                            "MODEL_POOL_PROVIDER_MODEL_REFERENCED");
                });
        repository.deleteModel(provider.id(), modelId);
    }

    @Override
    public void deleteProvider(String tenantId, String providerId) {
        ModelProvider provider = repository.findProviderByTenantAndId(tenantId, providerId)
                .orElseThrow(() -> new BusinessException(
                        "Model provider not found", HttpStatus.NOT_FOUND));
        if (poolRepository.isProviderReferenced(provider.id())) {
            throw new BusinessException(
                    "Model provider is referenced by ModelPool",
                    HttpStatus.CONFLICT,
                    "MODEL_POOL_PROVIDER_REFERENCED");
        }
        repository.deleteProvider(tenantId, provider.id());
    }

    @Override
    public void validateSelection(String tenantId, String providerId, String modelId) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        ModelProvider provider = repository.findProviderByTenantAndId(tenantId, providerId)
                .orElseThrow(() -> new BusinessException("Model provider not found", HttpStatus.BAD_REQUEST));
        if (!provider.enabled()) {
            throw new BusinessException("Model provider is disabled", HttpStatus.BAD_REQUEST);
        }
        if (modelId != null && !modelId.isBlank()
                && repository.findModelsByProviderId(provider.id()).stream()
                .noneMatch(model -> modelId.equals(model.modelId()))) {
            throw new BusinessException("Model is not configured for provider", HttpStatus.BAD_REQUEST);
        }
    }

    @Override
    public boolean isOwner(String providerId, String principalId) {
        return repository.findProviderById(providerId)
                .filter(provider -> principalId.equals(provider.ownerId()))
                .isPresent();
    }

    private ProviderModel saveModel(
            ModelProvider provider,
            String modelId,
            String displayName,
            int maxContextTokens,
            boolean isDefault,
            Instant now) {
        ProviderModel model = new ProviderModel(
                idGenerator.nextId(),
                provider.id(),
                modelId,
                displayName == null || displayName.isBlank() ? modelId : displayName,
                Math.max(1024, maxContextTokens),
                isDefault,
                now);
        repository.saveModel(model);
        return model;
    }

    private void clearDefault(String tenantId) {
        repository.findProvidersByTenantId(tenantId).stream()
                .filter(ModelProvider::isDefault)
                .forEach(provider -> repository.saveProvider(new ModelProvider(
                        provider.id(), provider.tenantId(), provider.ownerId(), provider.name(),
                        provider.providerType(), provider.baseUrl(), provider.encryptedApiKey(),
                        provider.authType(), provider.enabled(), false, provider.createdAt(),
                        provider.updatedAt(), provider.connectionStatus(), provider.lastTestedAt(),
                        provider.lastTestLatencyMs(), provider.lastTestErrorCode())));
    }

    private static boolean isSecretProvided(String apiKey) {
        return apiKey != null
                && !apiKey.isBlank()
                && !"configured".equalsIgnoreCase(apiKey)
                && !"********".equals(apiKey);
    }

    private static ModelProviderView toView(ModelProvider provider) {
        return new ModelProviderView(
                provider.id(),
                provider.tenantId(),
                provider.ownerId(),
                provider.name(),
                provider.providerType(),
                provider.baseUrl(),
                provider.encryptedApiKey() != null && !provider.encryptedApiKey().isBlank(),
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

    private static ProviderModelView toModelView(ProviderModel model) {
        return new ProviderModelView(
                model.id(),
                model.providerId(),
                model.modelId(),
                model.displayName(),
                model.maxContextTokens(),
                model.isDefault(),
                model.createdAt());
    }
}
