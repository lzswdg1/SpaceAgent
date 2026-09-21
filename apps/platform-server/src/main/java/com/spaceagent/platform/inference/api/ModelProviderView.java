package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.inference.domain.ProviderConnectionStatus;

import java.time.Instant;

/**
 * Public model provider query result. Secrets are represented as configured or
 * empty, never returned in clear text.
 */
public record ModelProviderView(
        String id,
        String tenantId,
        String ownerId,
        String name,
        String providerType,
        String baseUrl,
        boolean hasSecret,
        String authType,
        boolean enabled,
        boolean isDefault,
        ProviderConnectionStatus connectionStatus,
        Instant lastTestedAt,
        Integer lastTestLatencyMs,
        String lastTestErrorCode,
        Instant createdAt,
        Instant updatedAt) {
}
