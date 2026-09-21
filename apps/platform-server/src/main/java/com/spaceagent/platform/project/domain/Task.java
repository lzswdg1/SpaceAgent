package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Project-owned durable Task intent with mutually exclusive PROJECT or CHAT scope. */
public final class Task {

    private final String id;
    private final String projectId;
    private final String tenantId;
    private final String ownerUserId;
    private final String conversationId;
    private final String sourceMessageId;
    private final String parentTaskId;
    private final String title;
    private final String goal;
    private final String description;
    private final List<String> constraints;
    private final List<String> acceptanceCriteria;
    private final String currentTaskPlanId;
    private final TaskState state;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Task(
            String id,
            String projectId,
            String tenantId,
            String ownerUserId,
            String conversationId,
            String sourceMessageId,
            String parentTaskId,
            String title,
            String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria,
            String currentTaskPlanId,
            TaskState state,
            Instant createdAt,
            Instant updatedAt) {
        this.id = requireText(id, "id");
        this.projectId = normalizeOptional(projectId);
        this.tenantId = normalizeOptional(tenantId);
        this.ownerUserId = normalizeOptional(ownerUserId);
        this.conversationId = normalizeOptional(conversationId);
        this.sourceMessageId = normalizeOptional(sourceMessageId);
        if (this.projectId == null) {
            requireText(this.tenantId, "tenantId");
            requireText(this.ownerUserId, "ownerUserId");
            requireText(this.conversationId, "conversationId");
        } else if (this.conversationId != null || this.sourceMessageId != null) {
            throw new IllegalArgumentException("Project Task cannot carry Chat scope");
        }
        this.parentTaskId = normalizeOptional(parentTaskId);
        if (this.projectId == null
                && ((this.parentTaskId == null) == (this.sourceMessageId == null))) {
            throw new IllegalArgumentException(
                    "Chat Task must be either a source Message Root or a direct Child");
        }
        if (this.id.equals(this.parentTaskId)) {
            throw new IllegalArgumentException("Task cannot be its own parent");
        }
        this.title = normalizeTitle(title);
        this.goal = requireText(goal, "goal");
        this.description = description;
        this.constraints = normalizeList(constraints, "constraints");
        this.acceptanceCriteria = normalizeList(acceptanceCriteria, "acceptanceCriteria");
        this.currentTaskPlanId = normalizeOptional(currentTaskPlanId);
        if (this.projectId == null && this.parentTaskId != null && this.currentTaskPlanId != null) {
            throw new IllegalArgumentException("Chat Child Task cannot own a TaskPlan");
        }
        this.state = Objects.requireNonNull(state, "state");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Task create(
            String id,
            String projectId,
            String parentTaskId,
            String title,
            String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria,
            Instant now) {
        return new Task(
                id, projectId, null, null, null, null, parentTaskId, title, goal, description,
                constraints, acceptanceCriteria, null, TaskState.PENDING, now, now);
    }

    public static Task createProject(
            String id,
            String projectId,
            String tenantId,
            String ownerUserId,
            String parentTaskId,
            String title,
            String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria,
            Instant now) {
        return new Task(
                id, projectId, tenantId, ownerUserId, null, null, parentTaskId,
                title, goal, description, constraints, acceptanceCriteria,
                null, TaskState.PENDING, now, now);
    }

    public static Task createChatRoot(
            String id,
            String tenantId,
            String ownerUserId,
            String conversationId,
            String sourceMessageId,
            String title,
            String goal,
            List<String> acceptanceCriteria,
            Instant now) {
        return new Task(
                id, null, tenantId, ownerUserId, conversationId, sourceMessageId,
                null, title, goal, null, List.of(), acceptanceCriteria,
                null, TaskState.IN_PROGRESS, now, now);
    }

    public static Task createChatChild(
            String id,
            String tenantId,
            String ownerUserId,
            String conversationId,
            String parentTaskId,
            String title,
            String goal,
            List<String> acceptanceCriteria,
            Instant now) {
        return new Task(
                id, null, tenantId, ownerUserId, conversationId, null,
                parentTaskId, title, goal, null, List.of(), acceptanceCriteria,
                null, TaskState.PENDING, now, now);
    }

    public static Task restore(
            String id,
            String projectId,
            String parentTaskId,
            String title,
            String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria,
            TaskState state,
            Instant createdAt,
            Instant updatedAt) {
        return restore(
                id, projectId, parentTaskId, title, goal, description,
                constraints, acceptanceCriteria, null, state, createdAt, updatedAt);
    }

    public static Task restore(
            String id,
            String projectId,
            String parentTaskId,
            String title,
            String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria,
            String currentTaskPlanId,
            TaskState state,
            Instant createdAt,
            Instant updatedAt) {
        return new Task(
                id, projectId, null, null, null, null, parentTaskId, title, goal, description,
                constraints, acceptanceCriteria, currentTaskPlanId,
                state, createdAt, updatedAt);
    }

    public static Task restore(
            String id,
            String projectId,
            String tenantId,
            String ownerUserId,
            String conversationId,
            String sourceMessageId,
            String parentTaskId,
            String title,
            String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria,
            String currentTaskPlanId,
            TaskState state,
            Instant createdAt,
            Instant updatedAt) {
        return new Task(
                id, projectId, tenantId, ownerUserId, conversationId, sourceMessageId,
                parentTaskId, title, goal, description, constraints, acceptanceCriteria,
                currentTaskPlanId, state, createdAt, updatedAt);
    }

    public Task updateIntent(
            String nextTitle,
            String nextGoal,
            String nextDescription,
            List<String> nextConstraints,
            List<String> nextAcceptanceCriteria,
            Instant now) {
        requireMutable();
        return new Task(
                id, projectId, tenantId, ownerUserId, conversationId, sourceMessageId,
                parentTaskId, nextTitle, nextGoal, nextDescription,
                nextConstraints, nextAcceptanceCriteria, currentTaskPlanId,
                state, createdAt, now);
    }

    public Task markReady(Instant now) {
        if (state != TaskState.PENDING && state != TaskState.BLOCKED) {
            throw invalidTransition(TaskState.READY);
        }
        return withState(TaskState.READY, now);
    }

    public Task start(Instant now) {
        requireState(TaskState.READY, TaskState.IN_PROGRESS);
        return withState(TaskState.IN_PROGRESS, now);
    }

    public Task block(Instant now) {
        requireState(TaskState.IN_PROGRESS, TaskState.BLOCKED);
        return withState(TaskState.BLOCKED, now);
    }

    public Task complete(Instant now) {
        requireState(TaskState.IN_PROGRESS, TaskState.COMPLETED);
        return withState(TaskState.COMPLETED, now);
    }

    public Task fail(Instant now) {
        requireState(TaskState.IN_PROGRESS, TaskState.FAILED);
        return withState(TaskState.FAILED, now);
    }

    public Task cancel(Instant now) {
        if (state.isTerminal()) {
            throw invalidTransition(TaskState.CANCELLED);
        }
        return withState(TaskState.CANCELLED, now);
    }

    private Task withState(TaskState nextState, Instant now) {
        return new Task(
                id, projectId, tenantId, ownerUserId, conversationId, sourceMessageId,
                parentTaskId, title, goal, description,
                constraints, acceptanceCriteria, currentTaskPlanId, nextState, createdAt,
                Objects.requireNonNull(now, "now"));
    }

    public Task activatePlan(String taskPlanId, Instant now) {
        requireMutable();
        if (parentTaskId != null) {
            throw new IllegalStateException("Only a Root Task can activate a TaskPlan");
        }
        return new Task(
                id, projectId, tenantId, ownerUserId, conversationId, sourceMessageId,
                parentTaskId, title, goal, description,
                constraints, acceptanceCriteria, requireText(taskPlanId, "taskPlanId"),
                state, createdAt, Objects.requireNonNull(now, "now"));
    }

    public Task clearPlan(Instant now) {
        return new Task(
                id, projectId, tenantId, ownerUserId, conversationId, sourceMessageId,
                parentTaskId, title, goal, description,
                constraints, acceptanceCriteria, null, state, createdAt,
                Objects.requireNonNull(now, "now"));
    }

    private void requireMutable() {
        if (state.isTerminal()) {
            throw new IllegalStateException("Terminal Task intent cannot be modified");
        }
    }

    private void requireState(TaskState expected, TaskState next) {
        if (state != expected) {
            throw invalidTransition(next);
        }
    }

    private IllegalStateException invalidTransition(TaskState next) {
        return new IllegalStateException(
                "Task cannot transition from " + state + " to " + next);
    }

    private static String normalizeTitle(String value) {
        String title = requireText(value, "title");
        if (title.length() > 200) {
            throw new IllegalArgumentException("title must not exceed 200 characters");
        }
        return title;
    }

    private static List<String> normalizeList(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        return values.stream().map(value -> requireText(value, field + " item")).toList();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() {
        return id;
    }

    public String projectId() {
        return projectId;
    }

    public String tenantId() { return tenantId; }

    public String ownerUserId() { return ownerUserId; }

    public String conversationId() { return conversationId; }

    public String sourceMessageId() { return sourceMessageId; }

    public String parentTaskId() {
        return parentTaskId;
    }

    public String title() {
        return title;
    }

    public String goal() {
        return goal;
    }

    public String description() {
        return description;
    }

    public List<String> constraints() {
        return constraints;
    }

    public List<String> acceptanceCriteria() {
        return acceptanceCriteria;
    }

    public String currentTaskPlanId() {
        return currentTaskPlanId;
    }

    public TaskState state() {
        return state;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
