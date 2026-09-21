package com.spaceagent.platform.runtime.api;

public record ChatRecoveryCommand(String tenantId, String userId, String agentRunId, String reason) {
    public ChatRecoveryCommand {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (agentRunId == null || agentRunId.isBlank()) {
            throw new IllegalArgumentException("agentRunId must not be blank");
        }
        reason = reason == null || reason.isBlank() ? "chat runtime recovery" : reason;
    }
}
