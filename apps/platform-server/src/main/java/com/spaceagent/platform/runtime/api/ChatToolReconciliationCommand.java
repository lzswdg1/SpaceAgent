package com.spaceagent.platform.runtime.api;

public record ChatToolReconciliationCommand(
        String tenantId,
        String userId,
        String agentRunId,
        String toolCallId,
        long expectedRevision,
        String reason) {

    public ChatToolReconciliationCommand {
        require(tenantId, "tenantId");
        require(userId, "userId");
        require(agentRunId, "agentRunId");
        require(toolCallId, "toolCallId");
        require(reason, "reason");
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
