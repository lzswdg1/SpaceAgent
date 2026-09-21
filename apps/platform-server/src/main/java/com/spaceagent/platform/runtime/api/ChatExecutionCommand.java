package com.spaceagent.platform.runtime.api;

import java.util.List;

public record ChatExecutionCommand(
        String tenantId,
        String userId,
        String conversationId,
        String agentId,
        String message,
        String modelId,
        List<String> imageIds,
        String workspaceId) {

    public ChatExecutionCommand(String tenantId, String userId, String conversationId,
            String agentId, String message, String modelId, List<String> imageIds) {
        this(tenantId, userId, conversationId, agentId, message, modelId, imageIds, null);
    }

    public ChatExecutionCommand {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(userId, "userId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(message, "message");
        conversationId = normalize(conversationId);
        modelId = normalize(modelId);
        workspaceId = normalize(workspaceId);
        imageIds = imageIds == null ? List.of() : List.copyOf(imageIds);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
