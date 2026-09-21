package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.UserCleanupJobState;
import com.spaceagent.platform.identity.domain.UserCleanupStepKey;
import com.spaceagent.platform.identity.domain.UserCleanupStepState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Identity-owned durable User erasure control plane. Foreign cleanup remains owner-local. */
public interface UserCleanupApplicationApi {
    UserCleanupJobView enqueue(EnqueueCommand command);

    Optional<UserCleanupJobView> findJob(String userId);

    List<UserCleanupStepView> findSteps(String userId);

    Optional<UserCleanupClaimView> claimNext(ClaimNextCommand command);

    UserCleanupClaimView heartbeat(HeartbeatCommand command);

    UserCleanupStepView completeStep(CompleteStepCommand command);

    UserCleanupJobView defer(DeferCommand command);

    UserCleanupJobView fail(FailCommand command);

    UserCleanupJobView block(BlockCommand command);

    UserCleanupJobView complete(CompleteCommand command);

    record EnqueueCommand(String userId, UUID commandId, UUID requestedBy, String reasonHash) {
    }

    record ClaimNextCommand(String leaseOwner, int leaseSeconds) {
    }

    record HeartbeatCommand(String userId, String leaseOwner, String leaseToken,
                            long fencingToken, int leaseSeconds) {
    }

    record CompleteStepCommand(String userId, UserCleanupStepKey stepKey, String leaseOwner,
                               String leaseToken, long fencingToken) {
    }

    record DeferCommand(String userId, UserCleanupStepKey stepKey, String leaseOwner,
                        String leaseToken, long fencingToken, Instant nextAttemptAt,
                        String errorCode, String errorSummary) {
    }

    record FailCommand(String userId, UserCleanupStepKey stepKey, String leaseOwner,
                       String leaseToken, long fencingToken, String errorCode,
                       String errorSummary) {
    }

    record BlockCommand(String userId, UserCleanupStepKey stepKey, String leaseOwner,
                        String leaseToken, long fencingToken, String errorCode,
                        String errorSummary) {
    }

    record CompleteCommand(String userId, String leaseOwner, String leaseToken,
                           long fencingToken) {
    }

    record UserCleanupJobView(String userId, UUID commandId, UUID requestedBy,
                              UserCleanupJobState state, Instant retentionNotBefore,
                              Instant nextAttemptAt, int attempt, int maxAttempts,
                              String leaseOwner, String leaseToken, long fencingToken,
                              Instant leaseUntil, String lastErrorCode, String lastErrorSummary,
                              long revision, Instant createdAt, Instant updatedAt,
                              Instant completedAt) {
    }

    record UserCleanupStepView(String userId, UserCleanupStepKey stepKey, int sequence,
                               UserCleanupStepState state, int attempt, String lastErrorCode,
                               String lastErrorSummary, Instant createdAt, Instant updatedAt,
                               Instant completedAt) {
    }

    record UserCleanupClaimView(UserCleanupJobView job, List<UserCleanupStepView> steps) {
        public UserCleanupClaimView {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }
}
