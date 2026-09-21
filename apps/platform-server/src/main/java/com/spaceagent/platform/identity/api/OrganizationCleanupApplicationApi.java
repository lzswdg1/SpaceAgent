package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.identity.domain.OrganizationCleanupJobState;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepKey;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Identity-owned durable control plane. It performs no foreign resource deletion. */
public interface OrganizationCleanupApplicationApi {

    OrganizationCleanupJobView enqueue(String organizationId);

    /** Internal administrator command path only; execution still verifies the durable authorization. */
    default void resumeStorageDeletionAfterAdminApproval(String organizationId) { }

    Optional<OrganizationCleanupJobView> findJob(String organizationId);

    List<OrganizationCleanupStepView> findSteps(String organizationId);

    Optional<OrganizationCleanupClaimView> claimNext(ClaimNextCommand command);

    OrganizationCleanupClaimView heartbeat(HeartbeatCommand command);

    OrganizationCleanupStepView completeStep(CompleteStepCommand command);

    OrganizationCleanupJobView defer(DeferCommand command);

    OrganizationCleanupJobView fail(FailCommand command);
    OrganizationCleanupJobView block(BlockCommand command);

    OrganizationCleanupJobView complete(CompleteCommand command);

    record ClaimNextCommand(String leaseOwner, int leaseSeconds) {
    }

    record HeartbeatCommand(
            String organizationId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            int leaseSeconds) {
    }

    record CompleteStepCommand(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken) {
    }

    record DeferCommand(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary) {
    }

    record FailCommand(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            String errorCode,
            String errorSummary) {
    }
    record BlockCommand(
            String organizationId, OrganizationCleanupStepKey stepKey, String leaseOwner,
            String leaseToken, long fencingToken, String errorCode, String errorSummary) {
    }

    record CompleteCommand(
            String organizationId,
            String leaseOwner,
            String leaseToken,
            long fencingToken) {
    }

    record OrganizationCleanupJobView(
            String organizationId,
            OrganizationCleanupJobState state,
            Instant retentionNotBefore,
            Instant nextAttemptAt,
            int attempt,
            int maxAttempts,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant leaseUntil,
            String lastErrorCode,
            String lastErrorSummary,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
    }

    record OrganizationCleanupStepView(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            int sequence,
            OrganizationCleanupStepState state,
            int attempt,
            String lastErrorCode,
            String lastErrorSummary,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {
    }

    record OrganizationCleanupClaimView(
            OrganizationCleanupJobView job,
            List<OrganizationCleanupStepView> steps) {
    }
}
