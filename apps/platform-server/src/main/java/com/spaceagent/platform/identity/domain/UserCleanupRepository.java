package com.spaceagent.platform.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserCleanupRepository {
    void enqueue(UserCleanupJob job, List<UserCleanupStep> steps);

    Optional<UserCleanupJob> findJob(String userId);

    List<UserCleanupStep> findSteps(String userId);

    Optional<UserCleanupJob> claimNext(String leaseOwner, String leaseToken, int leaseSeconds, Instant now);

    Optional<UserCleanupJob> heartbeat(String userId, String leaseOwner, String leaseToken,
                                       long fencingToken, int leaseSeconds, Instant now);

    Optional<UserCleanupStep> completeStep(String userId, UserCleanupStepKey stepKey,
                                           String leaseOwner, String leaseToken,
                                           long fencingToken, Instant now);

    Optional<UserCleanupJob> defer(String userId, UserCleanupStepKey stepKey,
                                   String leaseOwner, String leaseToken, long fencingToken,
                                   Instant nextAttemptAt, String errorCode, String errorSummary,
                                   Instant now);

    Optional<UserCleanupJob> fail(String userId, UserCleanupStepKey stepKey,
                                  String leaseOwner, String leaseToken, long fencingToken,
                                  Instant nextAttemptAt, String errorCode, String errorSummary,
                                  Instant now);

    Optional<UserCleanupJob> block(String userId, UserCleanupStepKey stepKey,
                                   String leaseOwner, String leaseToken, long fencingToken,
                                   String errorCode, String errorSummary, Instant now);

    Optional<UserCleanupJob> complete(String userId, String leaseOwner, String leaseToken,
                                      long fencingToken, Instant now);

    long countIncompleteSteps(String userId);
}
