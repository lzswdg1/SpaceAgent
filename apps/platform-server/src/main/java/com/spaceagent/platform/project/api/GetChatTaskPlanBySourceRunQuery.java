package com.spaceagent.platform.project.api;

public record GetChatTaskPlanBySourceRunQuery(
        String tenantId,
        String userId,
        String conversationId,
        String sourceAgentRunId) {
}
