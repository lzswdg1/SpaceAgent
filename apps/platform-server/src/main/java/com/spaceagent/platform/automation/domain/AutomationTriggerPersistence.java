package com.spaceagent.platform.automation.domain;

import java.time.Instant;
import java.util.Objects;

/** Framework-independent durable records for event-trigger admission and delivery. */
public final class AutomationTriggerPersistence {

    private AutomationTriggerPersistence() {
    }

    public enum SubscriptionState {
        ACTIVE,
        PAUSED,
        ARCHIVED
    }

    public enum OccurrenceState {
        RECEIVED,
        READY,
        DISPATCHING,
        BLOCKED,
        DISPATCHED,
        SUCCEEDED,
        FAILED,
        UNKNOWN,
        DEAD_LETTERED
    }

    public enum DeliveryState {
        PENDING,
        CLAIMED,
        SUCCEEDED,
        FAILED,
        UNKNOWN,
        DEAD_LETTERED
    }

    public record Subscription(
            String id,
            String triggerVersionId,
            String triggerLineageId,
            String tenantId,
            String ownerId,
            AutomationTriggerType triggerType,
            String sourceBindingSha256,
            SubscriptionState state,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt) {

        public Subscription {
            require(id, "id");
            require(triggerVersionId, "triggerVersionId");
            require(triggerLineageId, "triggerLineageId");
            require(tenantId, "tenantId");
            require(ownerId, "ownerId");
            Objects.requireNonNull(triggerType, "triggerType");
            hash(sourceBindingSha256, "sourceBindingSha256");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (revision <= 0 || state == SubscriptionState.ARCHIVED && archivedAt == null
                    || state != SubscriptionState.ARCHIVED && archivedAt != null) {
                throw invalid("Subscription lifecycle is invalid");
            }
        }
    }

    public record Occurrence(
            String id,
            String triggerVersionId,
            String triggerLineageId,
            String subscriptionId,
            String tenantId,
            String ownerId,
            String sourceEventSha256,
            String payloadSha256,
            OccurrenceState state,
            Instant occurredAt,
            Instant admittedAt,
            long revision,
            Instant createdAt,
            Instant updatedAt) {

        public Occurrence {
            require(id, "id");
            require(triggerVersionId, "triggerVersionId");
            require(triggerLineageId, "triggerLineageId");
            require(subscriptionId, "subscriptionId");
            require(tenantId, "tenantId");
            require(ownerId, "ownerId");
            hash(sourceEventSha256, "sourceEventSha256");
            hash(payloadSha256, "payloadSha256");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (revision <= 0 || state == OccurrenceState.RECEIVED && admittedAt != null
                    || state != OccurrenceState.RECEIVED && admittedAt == null) {
                throw invalid("Occurrence lifecycle is invalid");
            }
        }
    }

    public record Delivery(
            String id,
            String occurrenceId,
            String tenantId,
            String ownerId,
            int attempt,
            DeliveryState state,
            Instant nextAttemptAt,
            String claimToken,
            String workerId,
            Instant leaseUntil,
            long fencingToken,
            String safeErrorCode,
            String evidenceSha256,
            long revision,
            Instant createdAt,
            Instant updatedAt) {

        public Delivery {
            require(id, "id");
            require(occurrenceId, "occurrenceId");
            require(tenantId, "tenantId");
            require(ownerId, "ownerId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (attempt <= 0 || revision <= 0 || fencingToken < 0) {
                throw invalid("Delivery counters are invalid");
            }
            boolean claimed = state == DeliveryState.CLAIMED;
            if (claimed != (claimToken != null && workerId != null && leaseUntil != null)
                    || !claimed && (claimToken != null || workerId != null || leaseUntil != null)) {
                throw invalid("Delivery claim is invalid");
            }
            if (safeErrorCode != null
                    && !safeErrorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw invalid("safeErrorCode is invalid");
            }
            if (evidenceSha256 != null) hash(evidenceSha256, "evidenceSha256");
        }
    }

    public record DeadLetter(
            String id,
            String occurrenceId,
            String deliveryId,
            String tenantId,
            String ownerId,
            String reasonCode,
            String evidenceSha256,
            Instant createdAt) {

        public DeadLetter {
            require(id, "id");
            require(occurrenceId, "occurrenceId");
            require(deliveryId, "deliveryId");
            require(tenantId, "tenantId");
            require(ownerId, "ownerId");
            if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw invalid("reasonCode is invalid");
            }
            hash(evidenceSha256, "evidenceSha256");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw invalid(field + " is invalid");
        }
    }

    private static void hash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid(field + " is invalid");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
