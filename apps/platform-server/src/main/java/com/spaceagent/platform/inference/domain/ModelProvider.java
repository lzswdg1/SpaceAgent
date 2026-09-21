package com.spaceagent.platform.inference.domain;

import java.time.Instant;

/**
 * A tenant-owned model provider registration. Provider execution belongs to the
 * inference module, not to agent configuration.
 */
public record ModelProvider(
        String id,
        String tenantId,
        String ownerId,
        String name,
        String providerType,
        String baseUrl,
        String encryptedApiKey,
        String authType,
        boolean enabled,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt,
        ProviderConnectionStatus connectionStatus,
        Instant lastTestedAt,
        Integer lastTestLatencyMs,
        String lastTestErrorCode) {

    public ModelProvider(
            String id,
            String tenantId,
            String ownerId,
            String name,
            String providerType,
            String baseUrl,
            String encryptedApiKey,
            String authType,
            boolean enabled,
            boolean isDefault,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id, tenantId, ownerId, name, providerType, baseUrl, encryptedApiKey,
                authType, enabled, isDefault, createdAt, updatedAt,
                enabled ? ProviderConnectionStatus.UNTESTED : ProviderConnectionStatus.DISABLED,
                null, null, null);
    }

    public ModelProvider resetConnectionStatus(boolean nextEnabled, Instant now) {
        return new ModelProvider(
                id, tenantId, ownerId, name, providerType, baseUrl, encryptedApiKey,
                authType, nextEnabled, isDefault, createdAt, now,
                nextEnabled ? ProviderConnectionStatus.UNTESTED : ProviderConnectionStatus.DISABLED,
                null, null, null);
    }

    public ModelProvider recordConnectionTest(
            ProviderConnectionProbeResult result,
            Instant testedAt) {
        return new ModelProvider(
                id, tenantId, ownerId, name, providerType, baseUrl, encryptedApiKey,
                authType, enabled, isDefault, createdAt, testedAt,
                result.success() ? ProviderConnectionStatus.ACTIVE : ProviderConnectionStatus.UNHEALTHY,
                testedAt, result.latencyMs(), result.success() ? null : result.errorCode());
    }
}
