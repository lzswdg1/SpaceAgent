package com.spaceagent.platform.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrganizationCleanupRepository {

    void enqueue(OrganizationCleanupJob job, List<OrganizationCleanupStep> steps);

    default void resumeStorageDeletionAfterAdminApproval(String organizationId, Instant now) { }

    Optional<OrganizationCleanupJob> findJob(String organizationId);

    List<OrganizationCleanupStep> findSteps(String organizationId);

    Optional<OrganizationCleanupJob> claimNext(
            String leaseOwner,
            String leaseToken,
            int leaseSeconds,
            Instant now);

    Optional<OrganizationCleanupJob> heartbeat(
            String organizationId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            int leaseSeconds,
            Instant now);

    Optional<OrganizationCleanupStep> completeStep(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant now);

    Optional<OrganizationCleanupJob> defer(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            Instant now);

    Optional<OrganizationCleanupJob> fail(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            Instant now);
    Optional<OrganizationCleanupJob> block(
            String organizationId, OrganizationCleanupStepKey stepKey, String leaseOwner,
            String leaseToken, long fencingToken, String errorCode, String errorSummary, Instant now);

    Optional<OrganizationCleanupJob> complete(
            String organizationId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant now);

    long countIncompleteSteps(String organizationId);
}
