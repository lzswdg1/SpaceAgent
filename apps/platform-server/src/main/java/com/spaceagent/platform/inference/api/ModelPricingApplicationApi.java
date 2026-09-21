package com.spaceagent.platform.inference.api;

import java.time.Instant;
import java.util.List;

public interface ModelPricingApplicationApi {
    PriceView createPrice(CreatePriceCommand command);
    List<PriceView> prices(String tenantId, String userId, String providerModelId);

    record CreatePriceCommand(
            String tenantId,
            String userId,
            String providerModelId,
            long inputMicrosPerMillionTokens,
            long outputMicrosPerMillionTokens,
            Instant effectiveFrom,
            Instant effectiveUntil) {
    }

    record PriceView(
            String id,
            String providerModelId,
            int version,
            long inputMicrosPerMillionTokens,
            long outputMicrosPerMillionTokens,
            String currency,
            Instant effectiveFrom,
            Instant effectiveUntil,
            Instant createdAt) {
    }
}
