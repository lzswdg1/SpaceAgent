package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.Objects;

/** Versioned PROJECT/CHAT plan lifecycle; its step structure is immutable after insertion. */
public record TaskPlan(
        String id,
        String projectId,
        String rootTaskId,
        int versionNumber,
        TaskPlanStatus status,
        String generatedByConfigurationHash,
        String createdBy,
        String approvedBy,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt,
        String tenantId,
        String ownerUserId,
        String conversationId,
        String sourceAgentRunId,
        String proposalHash,
        String strategySummary,
        String generatedByAgentId,
        String generatedByRunConfigurationSnapshotId) {

    public TaskPlan {
        id = requireText(id, "id");
        projectId = normalizeOptional(projectId);
        rootTaskId = requireText(rootTaskId, "rootTaskId");
        createdBy = requireText(createdBy, "createdBy");
        tenantId = normalizeOptional(tenantId);
        ownerUserId = normalizeOptional(ownerUserId);
        conversationId = normalizeOptional(conversationId);
        sourceAgentRunId = normalizeOptional(sourceAgentRunId);
        proposalHash = normalizeOptional(proposalHash);
        strategySummary = normalizeOptional(strategySummary);
        if (projectId == null) {
            tenantId = requireText(tenantId, "tenantId");
            ownerUserId = requireText(ownerUserId, "ownerUserId");
            conversationId = requireText(conversationId, "conversationId");
            sourceAgentRunId = requireText(sourceAgentRunId, "sourceAgentRunId");
            proposalHash = requireText(proposalHash, "proposalHash");
            strategySummary = requireText(strategySummary, "strategySummary");
        } else if (conversationId != null || sourceAgentRunId != null || proposalHash != null) {
            throw new IllegalArgumentException("Project TaskPlan cannot carry Chat scope");
        }
        if (versionNumber <= 0) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public TaskPlan(
            String id, String projectId, String rootTaskId, int versionNumber,
            TaskPlanStatus status, String generatedByConfigurationHash, String createdBy,
            String approvedBy, Instant approvedAt, Instant createdAt, Instant updatedAt,
            String tenantId, String ownerUserId, String conversationId,
            String sourceAgentRunId, String proposalHash, String strategySummary) {
        this(id, projectId, rootTaskId, versionNumber, status,
                generatedByConfigurationHash, createdBy, approvedBy, approvedAt, createdAt, updatedAt,
                tenantId, ownerUserId, conversationId, sourceAgentRunId, proposalHash,
                strategySummary, null, null);
    }

    public TaskPlan(
            String id, String projectId, String rootTaskId, int versionNumber,
            TaskPlanStatus status, String generatedByConfigurationHash, String createdBy,
            String approvedBy, Instant approvedAt, Instant createdAt, Instant updatedAt) {
        this(id, projectId, rootTaskId, versionNumber, status,
                generatedByConfigurationHash, createdBy, approvedBy, approvedAt, createdAt, updatedAt,
                null, null, null, null, null, null, null, null);
    }

    public static TaskPlan createProject(
            String id, String projectId, String tenantId, String ownerUserId, String rootTaskId,
            int versionNumber, TaskPlanStatus status, String generatedByConfigurationHash,
            String generatedByAgentId, String generatedByRunConfigurationSnapshotId,
            String createdBy, Instant createdAt) {
        return new TaskPlan(
                id, projectId, rootTaskId, versionNumber, status,
                generatedByConfigurationHash, createdBy, null, null, createdAt, createdAt,
                tenantId, ownerUserId, null, null, null, null,
                generatedByAgentId, generatedByRunConfigurationSnapshotId);
    }

    public static TaskPlan createProject(
            String id, String projectId, String tenantId, String ownerUserId, String rootTaskId,
            int versionNumber, TaskPlanStatus status, String generatedByConfigurationHash,
            String createdBy, Instant createdAt) {
        return createProject(id, projectId, tenantId, ownerUserId, rootTaskId, versionNumber,
                status, generatedByConfigurationHash, null, null, createdBy, createdAt);
    }

    public static TaskPlan createChatProposal(
            String id, String tenantId, String ownerUserId, String conversationId,
            String rootTaskId, int versionNumber, String generatedByConfigurationHash,
            String generatedByAgentId, String sourceAgentRunId, String proposalHash,
            String strategySummary, Instant createdAt) {
        return new TaskPlan(
                id, null, rootTaskId, versionNumber, TaskPlanStatus.PROPOSED,
                generatedByConfigurationHash, ownerUserId, null, null, createdAt, createdAt,
                tenantId, ownerUserId, conversationId, sourceAgentRunId, proposalHash,
                strategySummary, generatedByAgentId, sourceAgentRunId);
    }

    public static TaskPlan createChatProposal(
            String id, String tenantId, String ownerUserId, String conversationId,
            String rootTaskId, int versionNumber, String generatedByConfigurationHash,
            String sourceAgentRunId, String proposalHash, String strategySummary, Instant createdAt) {
        return createChatProposal(id, tenantId, ownerUserId, conversationId, rootTaskId,
                versionNumber, generatedByConfigurationHash, null, sourceAgentRunId,
                proposalHash, strategySummary, createdAt);
    }

    public TaskPlan propose(Instant at) {
        requireState(TaskPlanStatus.DRAFT, TaskPlanStatus.PROPOSED);
        return copy(TaskPlanStatus.PROPOSED, approvedBy, approvedAt, at);
    }

    public TaskPlan approve(String userId, Instant at) {
        requireState(TaskPlanStatus.PROPOSED, TaskPlanStatus.APPROVED);
        return copy(TaskPlanStatus.APPROVED, requireText(userId, "approvedBy"), at, at);
    }

    public TaskPlan activate(Instant at) {
        requireState(TaskPlanStatus.APPROVED, TaskPlanStatus.ACTIVE);
        return copy(TaskPlanStatus.ACTIVE, approvedBy, approvedAt, at);
    }

    public TaskPlan complete(Instant at) {
        requireState(TaskPlanStatus.ACTIVE, TaskPlanStatus.COMPLETED);
        return copy(TaskPlanStatus.COMPLETED, approvedBy, approvedAt, at);
    }

    public TaskPlan cancel(Instant at) {
        if (status.isTerminal()) {
            throw invalidTransition(TaskPlanStatus.CANCELLED);
        }
        return copy(TaskPlanStatus.CANCELLED, approvedBy, approvedAt, at);
    }

    private TaskPlan copy(
            TaskPlanStatus next,
            String nextApprovedBy,
            Instant nextApprovedAt,
            Instant at) {
        return new TaskPlan(
                id, projectId, rootTaskId, versionNumber, next,
                generatedByConfigurationHash, createdBy, nextApprovedBy, nextApprovedAt,
                createdAt, Objects.requireNonNull(at, "at"), tenantId, ownerUserId,
                conversationId, sourceAgentRunId, proposalHash, strategySummary,
                generatedByAgentId, generatedByRunConfigurationSnapshotId);
    }

    private void requireState(TaskPlanStatus expected, TaskPlanStatus next) {
        if (status != expected) {
            throw invalidTransition(next);
        }
    }

    private IllegalStateException invalidTransition(TaskPlanStatus next) {
        return new IllegalStateException(
                "TaskPlan cannot transition from " + status + " to " + next);
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
