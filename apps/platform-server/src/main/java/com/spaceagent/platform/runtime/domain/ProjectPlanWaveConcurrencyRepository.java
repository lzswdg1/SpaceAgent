package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.Optional;

public interface ProjectPlanWaveConcurrencyRepository {
    void insert(ProjectPlanWaveConcurrency value);
    Optional<ProjectPlanWaveConcurrency> findByExecutionId(String executionId);
    Optional<ProjectPlanWaveClaim> claim(String executionId, String tenantId, String ownerId, String taskPlanId,
                                         String stepId, String workerId, String token, Instant now, Instant leaseUntil);
    boolean release(ProjectPlanWaveClaim claim, Instant releasedAt);
    boolean bindJob(ProjectPlanWaveClaim claim, String jobId, Instant at);
    boolean synchronizeWithJob(
            String executionId, String stepId, String jobId, String jobClaimOwner,
            String jobClaimToken, long jobFencingToken, Instant jobLeaseUntil, Instant at);
    boolean releaseBound(
            String executionId, String stepId, String jobId, String jobClaimToken,
            long jobFencingToken, Instant releasedAt);
}
