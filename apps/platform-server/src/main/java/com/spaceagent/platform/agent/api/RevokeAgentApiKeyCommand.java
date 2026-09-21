package com.spaceagent.platform.agent.api;

public record RevokeAgentApiKeyCommand(
        String tenantId,
        String ownerId,
        String agentId,
        String keyId) {

    public RevokeAgentApiKeyCommand {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(ownerId, "ownerId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(keyId, "keyId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
