package com.spaceagent.platform.runtime.domain;

import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for the runtime run/step ledger.
 *
 * <p>The port is deliberately one transactional authority: AgentRun CAS, event sequence,
 * Worker Lease, and Continuation claim must share one PostgreSQL lock order. Persistence
 * implementations may split internal collaborators without exposing them cross-module.
 */
public interface RuntimeLedgerRepository {
    Optional<AgentRun> findRunById(String agentRunId);
    default Optional<AgentRun> findLatestChatRun(String tenantId,String ownerId,String conversationId){return Optional.empty();}

    List<RunStep> findStepsByRunId(String agentRunId);

    Optional<RunStep> findStepById(String runStepId);

    Optional<Checkpoint> findLatestCheckpoint(String agentRunId);

    Optional<Checkpoint> findLatestCheckpointByPhase(String agentRunId, String phase);

    List<Recovery> findRecoveriesByRunId(String agentRunId);

    Optional<Handoff> findHandoffById(String handoffId);

    List<Handoff> findHandoffsBySourceRunId(String sourceAgentRunId);

    List<RunEvent> findEventsByRunId(String agentRunId);

    List<RunEvent> findEventsByRunIdAfter(String agentRunId, long afterSequence, int limit);

    long findLatestEventSequence(String agentRunId);

    void createRun(AgentRun run);

    boolean compareAndSetRun(AgentRun expected, AgentRun updated);

    boolean compareAndSetRunFenced(
            AgentRun expected,
            AgentRun updated,
            String leaseToken,
            long fencingToken);

    void saveStep(RunStep step);

    void saveCheckpoint(Checkpoint checkpoint);

    void saveRecovery(Recovery recovery);

    void saveHandoff(Handoff handoff);

    RunEvent appendEvent(
            String agentRunId,
            RunEventType type,
            String payload,
            ExecutionCursor cursor,
            Instant createdAt);

    Optional<RunWorkerLease> findWorkerLease(String agentRunId);

    RunWorkerLeaseClaim claimWorkerLease(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            int leaseSeconds);

    Optional<RunWorkerLease> heartbeatWorkerLease(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            int leaseSeconds);

    boolean releaseWorkerLease(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            long fencingToken);

    boolean workerLeaseIsActive(
            String agentRunId,
            String leaseToken,
            long fencingToken);

    RuntimeContinuation saveContinuationIfAbsent(RuntimeContinuation continuation);

    Optional<RuntimeContinuation> findContinuationById(String continuationId);

    List<RuntimeContinuation> findContinuationsByRunId(String agentRunId);

    Optional<RuntimeContinuationClaim> claimNextContinuation(
            String leaseOwner,
            String leaseToken,
            int leaseSeconds);

    Optional<RuntimeContinuationClaim> heartbeatContinuation(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken,
            int leaseSeconds);

    Optional<RuntimeContinuation> completeContinuation(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken);

    Optional<RuntimeContinuation> failContinuation(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken,
            String error,
            int retryDelaySeconds);

    int cancelPendingContinuations(String agentRunId, String reason);
}
