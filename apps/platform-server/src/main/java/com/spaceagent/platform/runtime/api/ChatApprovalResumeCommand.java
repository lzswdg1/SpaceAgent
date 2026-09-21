package com.spaceagent.platform.runtime.api;

public record ChatApprovalResumeCommand(
        String tenantId,
        String userId,
        String agentRunId,
        String approvalId) {

    public ChatApprovalResumeCommand {
        require(tenantId, "tenantId");
        require(userId, "userId");
        require(agentRunId, "agentRunId");
        require(approvalId, "approvalId");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
