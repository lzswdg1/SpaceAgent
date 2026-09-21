package com.spaceagent.platform.runtime.domain;

/** Bounded exact state before any planned Chat inference or Tool effect occurs. */
public record ChatPlanReviewCheckpoint(
        String schema,
        String tenantId,
        String ownerId,
        String agentRunId,
        String conversationId,
        String agentId,
        String runConfigurationSnapshotId,
        String chatTaskId,
        String taskPlanId,
        String assistantReservationId,
        int assistantSequence,
        String userMessage) {

    public static final String SCHEMA = "chat-plan-review/v1";

    public ChatPlanReviewCheckpoint {
        if (!SCHEMA.equals(schema)) throw new IllegalArgumentException("Unsupported schema");
        require(tenantId, "tenantId");
        require(ownerId, "ownerId");
        require(agentRunId, "agentRunId");
        require(conversationId, "conversationId");
        require(agentId, "agentId");
        require(runConfigurationSnapshotId, "runConfigurationSnapshotId");
        require(chatTaskId, "chatTaskId");
        require(taskPlanId, "taskPlanId");
        require(assistantReservationId, "assistantReservationId");
        require(userMessage, "userMessage");
        if (assistantSequence < 0) throw new IllegalArgumentException("assistantSequence is invalid");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
