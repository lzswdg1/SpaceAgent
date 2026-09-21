package com.spaceagent.platform.inference.api;

public record TestProviderModelCommand(String tenantId, String providerId, String modelId) {

    public TestProviderModelCommand {
        require(tenantId, "tenantId");
        require(providerId, "providerId");
        require(modelId, "modelId");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
