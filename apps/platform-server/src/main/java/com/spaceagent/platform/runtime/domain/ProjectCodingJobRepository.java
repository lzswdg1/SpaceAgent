package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectCodingJobRepository {
    void insert(ProjectCodingJob job);
    Optional<ProjectCodingJob> findById(String id);
    Optional<ProjectCodingJob> findByIdForUpdate(String id);
    Optional<ProjectCodingJob> findByIdempotency(String tenantId, String ownerId, String hash);
    List<ProjectCodingJob> findActiveByTaskPlan(String taskPlanId, String ownerId);
    List<ProjectCodingJob> findActiveByExecutionId(String executionId, String ownerId);
    List<ProjectCodingJob> findByPlanStep(String planStepId, String ownerId, int offset, int limit);
    long countByPlanStep(String planStepId, String ownerId);
    Optional<ProjectCodingJob> claim(String owner, String token, Instant now,
                                     Instant leaseUntil, int maximumAttempts);
    boolean saveClaimed(ProjectCodingJob expected, ProjectCodingJob updated, Instant now);
    void saveLifecycle(ProjectCodingJob expected, ProjectCodingJob updated);
}
