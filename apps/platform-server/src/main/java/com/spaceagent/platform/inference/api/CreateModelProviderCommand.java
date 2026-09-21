package com.spaceagent.platform.inference.api;

import java.util.List;

/**
 * Public command for registering a tenant-owned model provider.
 */
public record CreateModelProviderCommand(
        String tenantId,
        String ownerId,
        String name,
        String providerType,
        String baseUrl,
        String apiKey,
        String authType,
        boolean enabled,
        boolean isDefault,
        List<ProviderModelDraft> models) {

    public CreateModelProviderCommand {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(ownerId, "ownerId");
        requireNonBlank(name, "name");
        requireNonBlank(providerType, "providerType");
        requireNonBlank(baseUrl, "baseUrl");
        requireNonBlank(apiKey, "apiKey");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public record ProviderModelDraft(String modelId, String displayName, Integer maxContextTokens) {
        public ProviderModelDraft {
            requireNonBlank(modelId, "modelId");
        }
    }
}
