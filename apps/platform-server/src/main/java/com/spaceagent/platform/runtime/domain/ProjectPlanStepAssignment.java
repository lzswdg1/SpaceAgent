package com.spaceagent.platform.runtime.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable Runtime-owned evidence for the exact Agent binding of one Project PlanStep. */
public record ProjectPlanStepAssignment(
        String id,
        String tenantId,
        String ownerId,
        String projectId,
        String taskPlanId,
        String planStepId,
        long revision,
        ProjectPlanStepAssignmentSource source,
        String agentId,
        String primaryConfigurationHash,
        String reviewerAgentId,
        String reviewerConfigurationHash,
        String modelPoolId,
        String capabilityHash,
        String configurationHash,
        String assignmentHash,
        Instant assignedAt) {

    public ProjectPlanStepAssignment {
        id = requireText(id, "id");
        tenantId = requireText(tenantId, "tenantId");
        ownerId = requireText(ownerId, "ownerId");
        projectId = requireText(projectId, "projectId");
        taskPlanId = requireText(taskPlanId, "taskPlanId");
        planStepId = requireText(planStepId, "planStepId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        source = Objects.requireNonNull(source, "source");
        agentId = requireText(agentId, "agentId");
        reviewerAgentId = requireText(reviewerAgentId, "reviewerAgentId");
        primaryConfigurationHash = normalizeOptional(primaryConfigurationHash);
        reviewerConfigurationHash = normalizeOptional(reviewerConfigurationHash);
        if (agentId.equals(reviewerAgentId)) {
            throw new IllegalArgumentException("reviewer must be distinct from the assigned Agent");
        }
        modelPoolId = normalizeOptional(modelPoolId);
        capabilityHash = requireHash(capabilityHash, "capabilityHash");
        configurationHash = requireHash(configurationHash, "configurationHash");
        assignmentHash = requireHash(assignmentHash, "assignmentHash");
        assignedAt = Objects.requireNonNull(assignedAt, "assignedAt");
        if (!assignmentHash.equals(computeHash(
                tenantId, ownerId, projectId, taskPlanId, planStepId, revision, source,
                agentId, primaryConfigurationHash, reviewerAgentId, reviewerConfigurationHash, modelPoolId,
                capabilityHash, configurationHash))) {
            throw new IllegalArgumentException("assignmentHash does not match immutable assignment fields");
        }
    }

    public static ProjectPlanStepAssignment fromPlanDefault(
            String id, String tenantId, String ownerId, String projectId, String taskPlanId,
            String planStepId, String agentId, String primaryConfigurationHash, String reviewerAgentId,
            String reviewerConfigurationHash, String modelPoolId, String capabilityHash,
            String configurationHash, Instant assignedAt) {
        return create(id, tenantId, ownerId, projectId, taskPlanId, planStepId, 1,
                ProjectPlanStepAssignmentSource.PLAN_DEFAULT, agentId, primaryConfigurationHash,
                reviewerAgentId, reviewerConfigurationHash, modelPoolId, capabilityHash,
                configurationHash, assignedAt);
    }

    public static ProjectPlanStepAssignment fromStepOverride(
            String id, String tenantId, String ownerId, String projectId, String taskPlanId,
            String planStepId, String agentId, String primaryConfigurationHash, String reviewerAgentId,
            String reviewerConfigurationHash, String modelPoolId, String capabilityHash,
            String configurationHash, Instant assignedAt) {
        return create(id, tenantId, ownerId, projectId, taskPlanId, planStepId, 1,
                ProjectPlanStepAssignmentSource.STEP_OVERRIDE, agentId, primaryConfigurationHash,
                reviewerAgentId, reviewerConfigurationHash, modelPoolId, capabilityHash,
                configurationHash, assignedAt);
    }

    public static ProjectPlanStepAssignment fromRevision(
            String id, String tenantId, String ownerId, String projectId, String taskPlanId,
            String planStepId, long revision, ProjectPlanStepAssignmentSource source,
            String agentId, String primaryConfigurationHash, String reviewerAgentId,
            String reviewerConfigurationHash, String modelPoolId, String capabilityHash,
            String configurationHash, Instant assignedAt) {
        return create(id, tenantId, ownerId, projectId, taskPlanId, planStepId, revision, source,
                agentId, primaryConfigurationHash, reviewerAgentId, reviewerConfigurationHash, modelPoolId,
                capabilityHash, configurationHash, assignedAt);
    }

    private static ProjectPlanStepAssignment create(
            String id, String tenantId, String ownerId, String projectId, String taskPlanId,
            String planStepId, long revision, ProjectPlanStepAssignmentSource source,
            String agentId, String primaryConfigurationHash, String reviewerAgentId,
            String reviewerConfigurationHash, String modelPoolId, String capabilityHash,
            String configurationHash, Instant assignedAt) {
        return new ProjectPlanStepAssignment(
                id, tenantId, ownerId, projectId, taskPlanId, planStepId, revision, source,
                agentId, primaryConfigurationHash, reviewerAgentId, reviewerConfigurationHash, modelPoolId,
                capabilityHash, configurationHash,
                computeHash(tenantId, ownerId, projectId, taskPlanId, planStepId, revision, source,
                        agentId, primaryConfigurationHash, reviewerAgentId, reviewerConfigurationHash,
                        modelPoolId, capabilityHash, configurationHash),
                assignedAt);
    }

    private static String computeHash(
            String tenantId, String ownerId, String projectId, String taskPlanId,
            String planStepId, long revision, ProjectPlanStepAssignmentSource source,
            String agentId, String primaryConfigurationHash, String reviewerAgentId,
            String reviewerConfigurationHash, String modelPoolId, String capabilityHash,
            String configurationHash) {
        String canonical = String.join("\n", tenantId, ownerId, projectId, taskPlanId, planStepId,
                Long.toString(revision), source.name(), agentId,
                primaryConfigurationHash == null ? "" : primaryConfigurationHash, reviewerAgentId,
                reviewerConfigurationHash == null ? "" : reviewerConfigurationHash,
                modelPoolId == null ? "" : modelPoolId, capabilityHash, configurationHash);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String requireHash(String value, String field) {
        String normalized = requireText(value, field);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
