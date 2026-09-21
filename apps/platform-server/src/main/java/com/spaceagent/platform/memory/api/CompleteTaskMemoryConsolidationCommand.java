package com.spaceagent.platform.memory.api;

/**
 * Public command for task-completion memory consolidation.
 *
 * <p>The task scope is always consolidated. Reusable knowledge is optionally promoted
 * to project and/or user scope when the corresponding identity is supplied.
 */
public record CompleteTaskMemoryConsolidationCommand(
        String taskId,
        String projectId,
        String userId,
        double acceptThreshold,
        double promotionThreshold) {

    public CompleteTaskMemoryConsolidationCommand {
        requireNonBlank(taskId, "taskId");
        if (acceptThreshold < 0 || acceptThreshold > 1) {
            throw new IllegalArgumentException("acceptThreshold must be between 0 and 1");
        }
        if (promotionThreshold < acceptThreshold || promotionThreshold > 1) {
            throw new IllegalArgumentException(
                    "promotionThreshold must be >= acceptThreshold and <= 1");
        }
    }

    public static CompleteTaskMemoryConsolidationCommand defaults(
            String taskId,
            String projectId,
            String userId) {
        return new CompleteTaskMemoryConsolidationCommand(taskId, projectId, userId, 0.55, 0.75);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
