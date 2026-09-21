package com.spaceagent.platform.runtime.api;

import java.time.Instant;
import java.util.Optional;

public interface ProjectPlanMergeBarrierApplicationApi {
    Optional<ClaimView> claimNext(String workerId, int leaseSeconds, int maximumAttempts);
    ClaimView heartbeat(ClaimCommand command, int leaseSeconds);
    void releaseKnownNoEffect(ClaimCommand command);
    void completeApplied(ClaimCommand command);
    void blockConflict(ClaimCommand command);
    void blockUnknown(ClaimCommand command, String safeErrorCode);

    record ClaimCommand(
            String executionId, String tenantId, String ownerId, String projectId,
            String taskPlanId, int applyIndex, String planStepId, String sourceMergeId,
            long barrierRevision, int attempt, String workerId, String claimToken, long fencingToken,
            Instant leaseUntil) {}

    record ClaimView(
            String executionId, String tenantId, String ownerId, String projectId,
            String taskPlanId, int applyIndex, String planStepId, String sourceMergeId,
            long barrierRevision, int attempt, String workerId, String claimToken, long fencingToken,
            Instant leaseUntil) {}
}
