package com.spaceagent.platform.inference.api;

/**
 * Public command for adding a model to an existing provider.
 */
public record AddProviderModelCommand(
        String tenantId,
        String providerId,
        String modelId,
        String displayName,
        Integer maxContextTokens,
        boolean isDefault) {

    public AddProviderModelCommand {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(providerId, "providerId");
        requireNonBlank(modelId, "modelId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
