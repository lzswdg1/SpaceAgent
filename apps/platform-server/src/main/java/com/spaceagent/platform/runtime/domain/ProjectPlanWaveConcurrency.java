package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.Objects;

/** Runtime-owned persisted concurrency budget for one Project plan execution. */
public record ProjectPlanWaveConcurrency(
        String executionId, String tenantId, String ownerId, String projectId, String taskPlanId,
        int maxParallelism, int activeClaims, long revision, Instant createdAt, Instant updatedAt) {
    public ProjectPlanWaveConcurrency {
        require(executionId); require(tenantId); require(ownerId); require(projectId); require(taskPlanId);
        if (maxParallelism < 1 || activeClaims < 0 || activeClaims > maxParallelism || revision < 1) {
            throw new IllegalArgumentException("project plan wave concurrency is invalid");
        }
        Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
    }
    private static void require(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("scope is required"); }
}
