package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaimType;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Public Java boundary for lease-protected asynchronous Runtime work. */
public interface RuntimeCoordinationApplicationApi {

    LeaseClaimView acquireLease(AcquireLeaseCommand command);

    LeaseView heartbeatLease(HeartbeatLeaseCommand command);

    void releaseLease(ReleaseLeaseCommand command);

    ContinuationView enqueue(EnqueueContinuationCommand command);

    Optional<ContinuationClaimView> claimNext(ClaimNextContinuationCommand command);

    ContinuationClaimView heartbeat(HeartbeatContinuationCommand command);

    ContinuationView complete(CompleteContinuationCommand command);

    ContinuationView fail(FailContinuationCommand command);

    Optional<ContinuationView> find(String continuationId);

    List<ContinuationView> findByRun(String agentRunId);

    record AcquireLeaseCommand(String agentRunId, String leaseOwner, int leaseSeconds) {}

    record HeartbeatLeaseCommand(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            int leaseSeconds) {}

    record ReleaseLeaseCommand(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            long fencingToken) {}

    record EnqueueContinuationCommand(
            String agentRunId,
            RuntimeContinuationType type,
            String deduplicationKey,
            String payload,
            Instant availableAt,
            int maxAttempts,
            String requestedId) {
        public EnqueueContinuationCommand(
                String agentRunId, RuntimeContinuationType type, String deduplicationKey,
                String payload, Instant availableAt, int maxAttempts) {
            this(agentRunId, type, deduplicationKey, payload, availableAt, maxAttempts, null);
        }
    }

    record ClaimNextContinuationCommand(String leaseOwner, int leaseSeconds) {}

    record HeartbeatContinuationCommand(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken,
            int leaseSeconds) {}

    record CompleteContinuationCommand(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken) {}

    record FailContinuationCommand(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken,
            String error,
            int retryDelaySeconds) {}

    record LeaseView(
            String agentRunId,
            String leaseToken,
            String leaseOwner,
            long fencingToken,
            Instant leaseUntil,
            long revision,
            Instant acquiredAt,
            Instant heartbeatAt,
            Instant releasedAt) {}

    record LeaseClaimView(RunWorkerLeaseClaimType type, LeaseView lease, String reason) {}

    record ContinuationView(
            String id,
            String agentRunId,
            RuntimeContinuationType type,
            String deduplicationKey,
            String payload,
            RuntimeContinuationState state,
            Instant availableAt,
            int attempt,
            int maxAttempts,
            String claimToken,
            String claimOwner,
            Long fencingToken,
            Instant leaseUntil,
            long revision,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {}

    record ContinuationClaimView(ContinuationView continuation, LeaseView lease) {}
}
