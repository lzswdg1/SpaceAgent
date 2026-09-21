package com.spaceagent.platform.automation.domain;

import java.time.Instant;
import java.util.Objects;

/** One immutable schedule occurrence with a durable, at-most-once execution lifecycle. */
public record AutomationExecution(
        String id,
        String scheduleId,
        String tenantId,
        String ownerId,
        String agentId,
        String configurationHash,
        String fireKey,
        Instant scheduledFor,
        AutomationTriggerType triggerType,
        AutomationExecutionState state,
        String operationHash,
        String approvalId,
        String conversationId,
        String dispatchRunId,
        String continuationId,
        String agentRunId,
        Instant startedAt,
        Instant completedAt,
        String error,
        int inputTokens,
        int outputTokens,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public AutomationExecution {
        require(id, "id");
        require(scheduleId, "scheduleId");
        require(tenantId, "tenantId");
        require(ownerId, "ownerId");
        require(agentId, "agentId");
        configurationHash = configurationHash == null || configurationHash.isBlank()
                ? null : configurationHash.trim();
        require(fireKey, "fireKey");
        Objects.requireNonNull(scheduledFor, "scheduledFor");
        triggerType = Objects.requireNonNull(triggerType, "triggerType");
        state = Objects.requireNonNull(state, "state");
        if (operationHash == null || !operationHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("operationHash must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (fireKey.length() > 200 || error != null && error.length() > 2_000
                || inputTokens < 0 || outputTokens < 0 || revision < 1) {
            throw new IllegalArgumentException("Invalid Automation execution fields");
        }
        boolean dispatched = conversationId != null || dispatchRunId != null || continuationId != null;
        if (dispatched && (conversationId == null || dispatchRunId == null
                || continuationId == null)) {
            throw new IllegalArgumentException("Dispatch references must be present together");
        }
        if (state == AutomationExecutionState.APPROVAL_REQUIRED && approvalId == null) {
            throw new IllegalArgumentException("Approval-required execution requires approvalId");
        }
        if ((state == AutomationExecutionState.QUEUED
                || state == AutomationExecutionState.RUNNING
                || state == AutomationExecutionState.SUCCEEDED
                || state == AutomationExecutionState.FAILED
                || state == AutomationExecutionState.UNKNOWN) && !dispatched) {
            throw new IllegalArgumentException("Dispatched execution state requires Run references");
        }
        if ((state == AutomationExecutionState.RUNNING) != (startedAt != null
                && completedAt == null) && state == AutomationExecutionState.RUNNING) {
            throw new IllegalArgumentException("Running execution requires startedAt only");
        }
        boolean terminal = state == AutomationExecutionState.SUCCEEDED
                || state == AutomationExecutionState.FAILED
                || state == AutomationExecutionState.UNKNOWN
                || state == AutomationExecutionState.REJECTED
                || state == AutomationExecutionState.EXPIRED
                || state == AutomationExecutionState.CANCELLED;
        if (terminal != (completedAt != null)) {
            throw new IllegalArgumentException("completedAt must match terminal execution state");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
