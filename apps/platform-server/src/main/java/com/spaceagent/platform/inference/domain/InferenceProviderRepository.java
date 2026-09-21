package com.spaceagent.platform.inference.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for model provider and provider-model persistence.
 */
public interface InferenceProviderRepository {

    Optional<ModelProvider> findProviderById(String id);

    Optional<ModelProvider> findProviderByTenantAndId(String tenantId, String id);

    List<ModelProvider> findProvidersByTenantId(String tenantId);

    List<ModelProvider> findProvidersByTenantAndIds(String tenantId, List<String> ids);

    void saveProvider(ModelProvider provider);

    void deleteProvider(String tenantId, String id);

    Optional<ProviderModel> findModelById(String modelId);

    List<ProviderModel> findModelsByIds(List<String> modelIds);

    List<ProviderModel> findModelsByProviderId(String providerId);

    void saveModel(ProviderModel model);

    void deleteModel(String providerId, String modelId);

    void deleteModelsByProviderId(String providerId);
}
