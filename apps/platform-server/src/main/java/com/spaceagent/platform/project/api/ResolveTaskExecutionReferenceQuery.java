package com.spaceagent.platform.project.api;

/** Resolves one canonical Project/Task/TaskPlan/PlanStep execution binding. */
public record ResolveTaskExecutionReferenceQuery(
        String tenantId,
        String userId,
        String projectId,
        String taskId,
        String taskPlanId,
        String planStepId) {

    public ResolveTaskExecutionReferenceQuery {
        requireText(tenantId, "tenantId");
        requireText(userId, "userId");
        requireText(projectId, "projectId");
        requireText(taskId, "taskId");
        requireText(taskPlanId, "taskPlanId");
        requireText(planStepId, "planStepId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
