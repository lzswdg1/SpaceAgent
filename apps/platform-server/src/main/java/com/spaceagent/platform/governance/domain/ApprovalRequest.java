package com.spaceagent.platform.governance.domain;

import java.time.Instant;

/** Durable approval request and its terminal decision/consumption audit fields. */
public record ApprovalRequest(
        String id,
        String tenantId,
        String requestedBy,
        GovernanceActionType actionType,
        String resourceType,
        String resourceId,
        String operationHash,
        String summary,
        ApprovalState state,
        Instant expiresAt,
        String decidedBy,
        Instant decidedAt,
        String decisionNote,
        Instant consumedAt,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public ApprovalRequest {
        requireNonBlank(id, "id");
        new ApprovalScope(
                tenantId, requestedBy, actionType, resourceType, resourceId, operationHash);
        requireNonBlank(summary, "summary");
        if (state == null || expiresAt == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("approval lifecycle fields are required");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("approval revision must be positive");
        }
    }

    public ApprovalScope scope() {
        return new ApprovalScope(
                tenantId, requestedBy, actionType, resourceType, resourceId, operationHash);
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
