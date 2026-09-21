package com.spaceagent.platform.inference.api;

import java.util.List;

/**
 * Public command for updating an existing tenant-owned model provider.
 *
 * <p>Null fields are left unchanged. A null or masked API key leaves the stored
 * secret unchanged.
 */
public record UpdateModelProviderCommand(
        String tenantId,
        String providerId,
        String name,
        String providerType,
        String baseUrl,
        String apiKey,
        String authType,
        Boolean enabled,
        Boolean isDefault,
        List<CreateModelProviderCommand.ProviderModelDraft> models) {

    public UpdateModelProviderCommand {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(providerId, "providerId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
