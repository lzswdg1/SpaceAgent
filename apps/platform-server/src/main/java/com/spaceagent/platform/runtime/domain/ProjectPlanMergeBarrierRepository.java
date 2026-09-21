package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectPlanMergeBarrierRepository {
    void create(ProjectPlanMergeBarrier barrier, List<ProjectPlanMergeBarrierEntry> entries);
    void createIfAbsent(ProjectPlanMergeBarrier barrier);
    boolean append(ProjectPlanMergeBarrierEntry entry);
    Optional<ProjectPlanMergeBarrier> find(String executionId);
    Optional<ProjectPlanMergeBarrierEntry> next(String executionId);
    boolean advance(ProjectPlanMergeBarrier barrier, ProjectPlanMergeBarrierEntry entry, Instant at);
    boolean block(ProjectPlanMergeBarrier barrier, Instant at);
    Optional<ProjectPlanMergeBarrierClaim> claimNext(
            String claimOwner, String claimToken, Instant now, Instant leaseUntil,
            int maximumAttempts);
    boolean heartbeat(ProjectPlanMergeBarrierClaim claim, Instant now, Instant leaseUntil);
    boolean releaseKnownNoEffect(ProjectPlanMergeBarrierClaim claim, Instant now);
    boolean completeApplied(ProjectPlanMergeBarrierClaim claim, Instant now);
    boolean blockClaim(ProjectPlanMergeBarrierClaim claim,
                       ProjectPlanMergeBarrierEntry.State state,
                       String safeErrorCode, Instant now);
    int blockExhausted(int maximumAttempts, Instant now);
}
