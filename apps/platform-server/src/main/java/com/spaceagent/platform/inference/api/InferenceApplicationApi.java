package com.spaceagent.platform.inference.api;

import java.util.List;
import java.util.Optional;

/**
 * Public inference application API used by other platform-server modules.
 */
public interface InferenceApplicationApi {

    ModelProviderView createProvider(CreateModelProviderCommand command);

    ModelProviderView updateProvider(UpdateModelProviderCommand command);

    Optional<ModelProviderView> findProvider(String providerId);

    List<ModelProviderView> listProvidersByTenant(String tenantId);

    ProviderModelView addModel(AddProviderModelCommand command);

    List<ProviderModelView> listModels(String providerId);

    void deleteModel(String tenantId, String providerId, String modelId);

    void deleteProvider(String tenantId, String providerId);

    void validateSelection(String tenantId, String providerId, String modelId);
}
