package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.Objects;

/** Durable asynchronous work item; PostgreSQL state is the retry/recovery truth. */
public record RuntimeContinuation(
        String id,
        String agentRunId,
        RuntimeContinuationType type,
        String deduplicationKey,
        String payload,
        RuntimeContinuationState state,
        Instant availableAt,
        int attempt,
        int maxAttempts,
        String claimToken,
        String claimOwner,
        Long fencingToken,
        Instant leaseUntil,
        long revision,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public RuntimeContinuation {
        require(id, "id");
        require(agentRunId, "agentRunId");
        require(deduplicationKey, "deduplicationKey");
        type = Objects.requireNonNull(type, "type");
        state = Objects.requireNonNull(state, "state");
        payload = payload == null || payload.isBlank() ? "{}" : payload;
        Objects.requireNonNull(availableAt, "availableAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (attempt < 0 || maxAttempts < 1 || attempt > maxAttempts || revision < 0) {
            throw new IllegalArgumentException("invalid continuation attempt/revision");
        }
        if (state == RuntimeContinuationState.CLAIMED
                && (claimToken == null || claimOwner == null || fencingToken == null
                || leaseUntil == null)) {
            throw new IllegalArgumentException("claimed continuation requires an active claim");
        }
        if (state != RuntimeContinuationState.CLAIMED
                && (claimToken != null || claimOwner != null || fencingToken != null
                || leaseUntil != null)) {
            throw new IllegalArgumentException("non-claimed continuation cannot retain a claim");
        }
        boolean terminal = state == RuntimeContinuationState.COMPLETED
                || state == RuntimeContinuationState.FAILED
                || state == RuntimeContinuationState.CANCELLED;
        if (terminal != (completedAt != null)) {
            throw new IllegalArgumentException("continuation completedAt must match terminal state");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
