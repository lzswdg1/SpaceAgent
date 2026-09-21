package com.spaceagent.platform.runtime.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/** Durable runtime execution aggregate for one Project TaskPlan run. */
public record ProjectPlanExecution(
        String id,
        String tenantId,
        String ownerId,
        String projectId,
        String projectDirectoryId,
        String conversationId,
        String sourceRepositoryId,
        String rootTaskId,
        String taskPlanId,
        String agentId,
        String primaryConfigurationHash,
        String reviewerAgentId,
        String baseRef,
        String idempotencyHash,
        String inputHash,
        ProjectPlanExecutionState state,
        String safeErrorCode,
        int attempt,
        long revision,
        Instant createdAt,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        ProjectPlanExecutionDesiredState desiredState,
        String controlReason) {

    public ProjectPlanExecution(
            String id,
            String tenantId,
            String ownerId,
            String projectId,
            String projectDirectoryId,
            String conversationId,
            String sourceRepositoryId,
            String rootTaskId,
            String taskPlanId,
            String agentId,
            String primaryConfigurationHash,
            String reviewerAgentId,
            String baseRef,
            String idempotencyHash,
            String inputHash,
            ProjectPlanExecutionState state,
            String safeErrorCode,
            int attempt,
            long revision,
            Instant createdAt,
            Instant startedAt,
            Instant updatedAt,
            Instant completedAt) {
        this(id, tenantId, ownerId, projectId, projectDirectoryId, conversationId,
                sourceRepositoryId, rootTaskId, taskPlanId, agentId, primaryConfigurationHash,
                reviewerAgentId, baseRef, idempotencyHash, inputHash, state,
                safeErrorCode, attempt, revision, createdAt, startedAt, updatedAt,
                completedAt, defaultDesiredState(state), defaultControlReason(state, safeErrorCode));
    }

    public ProjectPlanExecution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(projectDirectoryId, "projectDirectoryId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(sourceRepositoryId, "sourceRepositoryId");
        Objects.requireNonNull(rootTaskId, "rootTaskId");
        Objects.requireNonNull(taskPlanId, "taskPlanId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(reviewerAgentId, "reviewerAgentId");
        Objects.requireNonNull(baseRef, "baseRef");
        Objects.requireNonNull(idempotencyHash, "idempotencyHash");
        Objects.requireNonNull(inputHash, "inputHash");
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (desiredState == null) {
            throw new IllegalArgumentException("desiredState is required");
        }
        if (controlReason != null) {
            controlReason = normalizeControlReason(controlReason);
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt is invalid");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision is invalid");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("timestamps are invalid");
        }
    }

    public ProjectPlanExecutionControl control() {
        return new ProjectPlanExecutionControl(state, desiredState, controlReason, revision);
    }

    public ProjectPlanExecution withControl(
            ProjectPlanExecutionControl control, Instant changedAt) {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(changedAt, "changedAt");
        String nextSafeErrorCode = control.state() == ProjectPlanExecutionState.BLOCKED
                ? control.reason()
                : control.state() == ProjectPlanExecutionState.FAILED
                ? safeErrorCode : null;
        Instant nextCompletedAt = control.state() == ProjectPlanExecutionState.CANCELLED
                || control.state() == ProjectPlanExecutionState.BLOCKED
                ? changedAt : completedAt;
        return new ProjectPlanExecution(
                id, tenantId, ownerId, projectId, projectDirectoryId, conversationId,
                sourceRepositoryId, rootTaskId, taskPlanId, agentId, primaryConfigurationHash,
                reviewerAgentId, baseRef, idempotencyHash, inputHash, control.state(),
                nextSafeErrorCode, attempt, control.revision(), createdAt, startedAt,
                changedAt, nextCompletedAt, control.desiredState(), control.reason());
    }

    private static ProjectPlanExecutionDesiredState defaultDesiredState(
            ProjectPlanExecutionState state) {
        return switch (state) {
            case PAUSING, PAUSED -> ProjectPlanExecutionDesiredState.PAUSED;
            case CANCELLING, CANCELLED -> ProjectPlanExecutionDesiredState.CANCELLED;
            default -> ProjectPlanExecutionDesiredState.RUNNING;
        };
    }

    private static String defaultControlReason(
            ProjectPlanExecutionState state, String safeErrorCode) {
        return switch (state) {
            case PAUSING, PAUSED, CANCELLING, CANCELLED -> safeErrorCode;
            default -> null;
        };
    }

    private static String normalizeControlReason(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.getBytes(StandardCharsets.UTF_8).length > 240
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("controlReason is invalid");
        }
        return normalized;
    }
}
