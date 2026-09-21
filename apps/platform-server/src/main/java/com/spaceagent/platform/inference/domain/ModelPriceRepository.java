package com.spaceagent.platform.inference.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ModelPriceRepository {
    void save(ModelPrice price);
    int nextVersion(String providerModelId);
    Optional<ModelPrice> findEffective(String providerModelId, Instant at);
    List<ModelPrice> findEffectiveByProviderModelIds(List<String> providerModelIds, Instant at);
    List<ModelPrice> findByProviderModelId(String providerModelId);
    boolean overlaps(String providerModelId, Instant from, Instant until);
}
