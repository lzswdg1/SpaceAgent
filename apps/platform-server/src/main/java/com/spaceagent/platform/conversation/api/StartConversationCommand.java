package com.spaceagent.platform.conversation.api;

/**
 * Public command for starting a conversation.
 */
public record StartConversationCommand(
        String projectId,
        String projectDirectoryId,
        String taskId,
        String activeTaskId,
        String tenantId,
        String userId,
        String agentId,
        String title,
        String requestedId) {

    public StartConversationCommand {
        requireNonBlank(userId, "userId");
        projectId = normalizeOptional(projectId);
        projectDirectoryId = normalizeOptional(projectDirectoryId);
        taskId = normalizeOptional(taskId);
        activeTaskId = normalizeOptional(activeTaskId);
        tenantId = normalizeOptional(tenantId);
        agentId = normalizeOptional(agentId);
        requestedId = normalizeOptional(requestedId);
    }

    public StartConversationCommand(
            String projectId, String projectDirectoryId, String taskId, String activeTaskId,
            String tenantId, String userId, String agentId, String title) {
        this(projectId, projectDirectoryId, taskId, activeTaskId, tenantId, userId,
                agentId, title, null);
    }

    /** Compatibility constructor before explicit active Task support. */
    public StartConversationCommand(
            String projectId,
            String taskId,
            String tenantId,
            String userId,
            String agentId,
            String title) {
        this(projectId, null, taskId, null, tenantId, userId, agentId, title, null);
    }

    /** Compatibility constructor before ProjectDirectory hierarchy. */
    public StartConversationCommand(
            String projectId,
            String taskId,
            String activeTaskId,
            String tenantId,
            String userId,
            String agentId,
            String title) {
        this(projectId, null, taskId, activeTaskId, tenantId, userId, agentId, title, null);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
