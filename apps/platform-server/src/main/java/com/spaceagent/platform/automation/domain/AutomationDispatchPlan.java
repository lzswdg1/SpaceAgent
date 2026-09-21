package com.spaceagent.platform.automation.domain;

import java.time.Instant;

/** Stable, resumable identity plan for one event occurrence dispatch. */
public record AutomationDispatchPlan(
        String id, String occurrenceId, String deliveryId, String tenantId, String ownerId,
        String operationHash, String approvalId, String conversationId, String dispatchRunId,
        String continuationId, Phase phase, String safeErrorCode, long revision,
        Instant createdAt, Instant updatedAt) {
    public enum Phase { RESERVED, CONVERSATION_READY, RUN_READY, CONTINUATION_READY, BLOCKED, UNKNOWN }

    public AutomationDispatchPlan {
        if (id == null || occurrenceId == null || deliveryId == null || tenantId == null
                || ownerId == null || conversationId == null || dispatchRunId == null
                || continuationId == null || operationHash == null
                || !operationHash.matches("sha256:[0-9a-f]{64}") || phase == null
                || revision < 1 || createdAt == null || updatedAt == null
                || (phase == Phase.BLOCKED || phase == Phase.UNKNOWN) != (safeErrorCode != null)) {
            throw new IllegalArgumentException("Automation dispatch plan is invalid");
        }
    }

    public AutomationDispatchPlan advance(Phase next, Instant now) {
        if (next.ordinal() < phase.ordinal() || phase == Phase.BLOCKED || phase == Phase.UNKNOWN) {
            throw new IllegalStateException("Automation dispatch phase cannot advance");
        }
        return copy(next, approvalId, null, now);
    }

    public AutomationDispatchPlan block(String approval, String code, Instant now) {
        return copy(Phase.BLOCKED, approval, code, now);
    }

    public AutomationDispatchPlan resume(String approval, Instant now) {
        if (phase != Phase.BLOCKED) throw new IllegalStateException("Dispatch plan is not blocked");
        return copy(Phase.RESERVED, approval, null, now);
    }

    public AutomationDispatchPlan retryDelivery(String nextDeliveryId, Instant now) {
        if (phase != Phase.RESERVED) throw new IllegalStateException("Dispatch effects may already exist");
        return new AutomationDispatchPlan(id, occurrenceId, nextDeliveryId, tenantId, ownerId,
                operationHash, approvalId, conversationId, dispatchRunId, continuationId,
                phase, null, revision + 1, createdAt, now);
    }

    public AutomationDispatchPlan unknown(String code, Instant now) {
        return copy(Phase.UNKNOWN, approvalId, code, now);
    }

    private AutomationDispatchPlan copy(Phase next, String approval, String error, Instant now) {
        return new AutomationDispatchPlan(id, occurrenceId, deliveryId, tenantId, ownerId,
                operationHash, approval, conversationId, dispatchRunId, continuationId,
                next, error, revision + 1, createdAt, now);
    }
}
