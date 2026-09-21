package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable structural step; M18 will own execution transitions. */
public record PlanStep(
        String id,
        String taskPlanId,
        String projectId,
        String stepKey,
        int sequence,
        String childTaskId,
        String requiredCapability,
        String preferredAgentId,
        String expectedOutput,
        List<String> acceptanceCriteria,
        boolean approvalRequired,
        PlanStepState state,
        Instant createdAt,
        Instant updatedAt,
        String conversationId) {

    public PlanStep {
        id = requireText(id, "id");
        taskPlanId = requireText(taskPlanId, "taskPlanId");
        projectId = normalizeOptional(projectId);
        conversationId = normalizeOptional(conversationId);
        if ((projectId == null) == (conversationId == null)) {
            throw new IllegalArgumentException(
                    "PlanStep must have exactly one Project or Chat Conversation scope");
        }
        stepKey = requireText(stepKey, "stepKey");
        if (stepKey.length() > 64) {
            throw new IllegalArgumentException("stepKey must not exceed 64 characters");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        childTaskId = requireText(childTaskId, "childTaskId");
        expectedOutput = requireText(expectedOutput, "expectedOutput");
        acceptanceCriteria = List.copyOf(Objects.requireNonNull(
                acceptanceCriteria, "acceptanceCriteria"));
        state = Objects.requireNonNull(state, "state");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public PlanStep(
            String id, String taskPlanId, String projectId, String stepKey, int sequence,
            String childTaskId, String requiredCapability, String preferredAgentId,
            String expectedOutput, List<String> acceptanceCriteria, boolean approvalRequired,
            PlanStepState state, Instant createdAt, Instant updatedAt) {
        this(id, taskPlanId, projectId, stepKey, sequence, childTaskId, requiredCapability,
                preferredAgentId, expectedOutput, acceptanceCriteria, approvalRequired, state,
                createdAt, updatedAt, null);
    }

    public PlanStep start(Instant at) {
        if (state != PlanStepState.PENDING
                && state != PlanStepState.READY
                && state != PlanStepState.BLOCKED) {
            throw invalidTransition(PlanStepState.IN_PROGRESS);
        }
        return withState(PlanStepState.IN_PROGRESS, at);
    }

    public PlanStep complete(Instant at) {
        requireState(PlanStepState.IN_PROGRESS, PlanStepState.COMPLETED);
        return withState(PlanStepState.COMPLETED, at);
    }

    public PlanStep fail(Instant at) {
        if (state == PlanStepState.COMPLETED
                || state == PlanStepState.FAILED
                || state == PlanStepState.CANCELLED) {
            throw invalidTransition(PlanStepState.FAILED);
        }
        return withState(PlanStepState.FAILED, at);
    }

    public PlanStep cancel(Instant at) {
        if (state == PlanStepState.COMPLETED
                || state == PlanStepState.FAILED
                || state == PlanStepState.CANCELLED) {
            throw invalidTransition(PlanStepState.CANCELLED);
        }
        return withState(PlanStepState.CANCELLED, at);
    }

    private PlanStep withState(PlanStepState next, Instant at) {
        return new PlanStep(
                id, taskPlanId, projectId, stepKey, sequence, childTaskId,
                requiredCapability, preferredAgentId, expectedOutput,
                acceptanceCriteria, approvalRequired, next, createdAt,
                Objects.requireNonNull(at, "at"), conversationId);
    }

    private void requireState(PlanStepState expected, PlanStepState next) {
        if (state != expected) {
            throw invalidTransition(next);
        }
    }

    private IllegalStateException invalidTransition(PlanStepState next) {
        return new IllegalStateException(
                "PlanStep cannot transition from " + state + " to " + next);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
