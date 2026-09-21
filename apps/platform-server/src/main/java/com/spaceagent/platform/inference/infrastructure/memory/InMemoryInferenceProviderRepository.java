package com.spaceagent.platform.inference.infrastructure.memory;

import com.spaceagent.platform.inference.domain.InferenceProviderRepository;
import com.spaceagent.platform.inference.domain.ModelProvider;
import com.spaceagent.platform.inference.domain.ProviderModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory scaffolding for inference provider persistence.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryInferenceProviderRepository implements InferenceProviderRepository {

    private final Map<String, ModelProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, ProviderModel> models = new ConcurrentHashMap<>();

    @Override
    public Optional<ModelProvider> findProviderById(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    @Override
    public Optional<ModelProvider> findProviderByTenantAndId(String tenantId, String id) {
        return findProviderById(id).filter(provider -> tenantId.equals(provider.tenantId()));
    }

    @Override
    public List<ModelProvider> findProvidersByTenantId(String tenantId) {
        return providers.values().stream()
                .filter(provider -> tenantId.equals(provider.tenantId()))
                .sorted(Comparator.comparing(ModelProvider::createdAt))
                .toList();
    }

    @Override
    public List<ModelProvider> findProvidersByTenantAndIds(String tenantId, List<String> ids) {
        java.util.Set<String> selected = java.util.Set.copyOf(ids);
        return providers.values().stream()
                .filter(provider -> tenantId.equals(provider.tenantId()) && selected.contains(provider.id()))
                .toList();
    }

    @Override
    public void saveProvider(ModelProvider provider) {
        providers.put(provider.id(), provider);
    }

    @Override
    public void deleteProvider(String tenantId, String id) {
        findProviderByTenantAndId(tenantId, id).ifPresent(provider -> {
            models.values().removeIf(model -> provider.id().equals(model.providerId()));
            providers.remove(provider.id());
        });
    }

    @Override
    public Optional<ProviderModel> findModelById(String modelId) {
        return Optional.ofNullable(models.get(modelId));
    }

    @Override
    public List<ProviderModel> findModelsByIds(List<String> modelIds) {
        return modelIds.stream().distinct().map(models::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<ProviderModel> findModelsByProviderId(String providerId) {
        return models.values().stream()
                .filter(model -> providerId.equals(model.providerId()))
                .sorted(Comparator.comparing(ProviderModel::createdAt))
                .toList();
    }

    @Override
    public void saveModel(ProviderModel model) {
        models.put(model.id(), model);
    }

    @Override
    public void deleteModel(String providerId, String modelId) {
        models.values().removeIf(model -> providerId.equals(model.providerId()) && modelId.equals(model.modelId()));
    }

    @Override
    public void deleteModelsByProviderId(String providerId) {
        models.values().removeIf(model -> providerId.equals(model.providerId()));
    }
}
