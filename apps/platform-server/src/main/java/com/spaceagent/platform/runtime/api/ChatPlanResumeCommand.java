package com.spaceagent.platform.runtime.api;

public record ChatPlanResumeCommand(
        String tenantId, String userId, String agentRunId, String taskPlanId) {
    public ChatPlanResumeCommand {
        require(tenantId, "tenantId"); require(userId, "userId");
        require(agentRunId, "agentRunId"); require(taskPlanId, "taskPlanId");
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
