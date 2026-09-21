package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.AgentRun;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.Checkpoint;
import com.spaceagent.platform.runtime.domain.Handoff;
import com.spaceagent.platform.runtime.domain.Recovery;
import com.spaceagent.platform.runtime.domain.RunEvent;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.domain.RunStep;
import com.spaceagent.platform.runtime.domain.RunWorkerLease;
import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaim;
import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaimType;
import com.spaceagent.platform.runtime.domain.RuntimeContinuation;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationClaim;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationState;
import com.spaceagent.platform.runtime.domain.RuntimeLedgerRepository;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory parity adapter for tests/local mode; production authority is PostgreSQL. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryRuntimeLedgerRepository implements RuntimeLedgerRepository {

    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Map<String, RunStep> steps = new ConcurrentHashMap<>();
    private final Map<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();
    private final Map<String, Recovery> recoveries = new ConcurrentHashMap<>();
    private final Map<String, Handoff> handoffs = new ConcurrentHashMap<>();
    private final Map<String, RunEvent> events = new ConcurrentHashMap<>();
    private final Map<String, RunWorkerLease> leases = new ConcurrentHashMap<>();
    private final Map<String, RuntimeContinuation> continuations = new ConcurrentHashMap<>();
    private final TimeProvider timeProvider;

    public InMemoryRuntimeLedgerRepository() {
        this(Instant::now);
    }

    @Autowired
    public InMemoryRuntimeLedgerRepository(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    @Override
    public Optional<AgentRun> findRunById(String agentRunId) {
        return Optional.ofNullable(runs.get(agentRunId));
    }

    @Override public Optional<AgentRun> findLatestChatRun(String tenantId,String ownerId,String conversationId){
        return runs.values().stream().filter(run->tenantId.equals(run.tenantId())&&ownerId.equals(run.ownerId())
                &&conversationId.equals(run.conversationId())&&run.projectId()==null)
                .max(Comparator.comparing(AgentRun::createdAt).thenComparing(AgentRun::id));
    }

    @Override
    public List<RunStep> findStepsByRunId(String agentRunId) {
        return steps.values().stream()
                .filter(step -> agentRunId.equals(step.agentRunId()))
                .sorted(Comparator.comparingInt(RunStep::sequence))
                .toList();
    }

    @Override
    public Optional<RunStep> findStepById(String runStepId) {
        return Optional.ofNullable(steps.get(runStepId));
    }

    @Override
    public Optional<Checkpoint> findLatestCheckpoint(String agentRunId) {
        return checkpoints.values().stream()
                .filter(checkpoint -> agentRunId.equals(checkpoint.agentRunId()))
                .max(Comparator.comparingInt(Checkpoint::sequence));
    }

    @Override
    public Optional<Checkpoint> findLatestCheckpointByPhase(String agentRunId, String phase) {
        String compact = "\"phase\":\"" + phase + "\"";
        String spaced = "\"phase\": \"" + phase + "\"";
        return checkpoints.values().stream()
                .filter(checkpoint -> agentRunId.equals(checkpoint.agentRunId()))
                .filter(checkpoint -> checkpoint.stateSnapshot().contains(compact)
                        || checkpoint.stateSnapshot().contains(spaced))
                .max(Comparator.comparingInt(Checkpoint::sequence));
    }

    @Override
    public List<Recovery> findRecoveriesByRunId(String agentRunId) {
        return recoveries.values().stream()
                .filter(recovery -> agentRunId.equals(recovery.agentRunId()))
                .sorted(Comparator.comparingInt(Recovery::attempt))
                .toList();
    }

    @Override
    public Optional<Handoff> findHandoffById(String handoffId) {
        return Optional.ofNullable(handoffs.get(handoffId));
    }

    @Override
    public List<Handoff> findHandoffsBySourceRunId(String sourceAgentRunId) {
        return handoffs.values().stream()
                .filter(handoff -> sourceAgentRunId.equals(handoff.sourceAgentRunId()))
                .sorted(Comparator.comparing(Handoff::createdAt))
                .toList();
    }

    @Override
    public List<RunEvent> findEventsByRunId(String agentRunId) {
        return findEventsByRunIdAfter(agentRunId, -1, Integer.MAX_VALUE);
    }

    @Override
    public List<RunEvent> findEventsByRunIdAfter(String agentRunId, long afterSequence, int limit) {
        return events.values().stream()
                .filter(event -> agentRunId.equals(event.agentRunId()))
                .filter(event -> event.sequence() > afterSequence)
                .sorted(Comparator.comparingLong(RunEvent::sequence))
                .limit(limit)
                .toList();
    }

    @Override
    public long findLatestEventSequence(String agentRunId) {
        return events.values().stream()
                .filter(event -> agentRunId.equals(event.agentRunId()))
                .mapToLong(RunEvent::sequence)
                .max()
                .orElse(-1L);
    }

    @Override
    public synchronized void createRun(AgentRun run) {
        if (runs.putIfAbsent(run.id(), run) != null) {
            throw new IllegalStateException("AgentRun already exists: " + run.id());
        }
    }

    @Override
    public synchronized boolean compareAndSetRun(AgentRun expected, AgentRun updated) {
        requireNextRevision(expected, updated);
        AgentRun current = runs.get(expected.id());
        if (current == null || current.revision() != expected.revision()) {
            return false;
        }
        runs.put(updated.id(), updated);
        return true;
    }

    @Override
    public synchronized boolean compareAndSetRunFenced(
            AgentRun expected,
            AgentRun updated,
            String leaseToken,
            long fencingToken) {
        if (!workerLeaseIsActive(expected.id(), leaseToken, fencingToken)) {
            return false;
        }
        return compareAndSetRun(expected, updated);
    }

    @Override
    public synchronized void saveStep(RunStep step) {
        boolean duplicate = steps.values().stream().anyMatch(existing ->
                !existing.id().equals(step.id())
                        && existing.agentRunId().equals(step.agentRunId())
                        && existing.sequence() == step.sequence());
        if (duplicate) {
            throw new IllegalStateException("RunStep sequence already exists");
        }
        steps.put(step.id(), step);
    }

    @Override
    public synchronized void saveCheckpoint(Checkpoint checkpoint) {
        boolean duplicate = checkpoints.values().stream().anyMatch(existing ->
                !existing.id().equals(checkpoint.id())
                        && existing.agentRunId().equals(checkpoint.agentRunId())
                        && existing.sequence() == checkpoint.sequence());
        if (duplicate) {
            throw new IllegalStateException("Checkpoint sequence already exists");
        }
        checkpoints.put(checkpoint.id(), checkpoint);
    }

    @Override
    public synchronized void saveRecovery(Recovery recovery) {
        boolean duplicate = recoveries.values().stream().anyMatch(existing ->
                !existing.id().equals(recovery.id())
                        && existing.agentRunId().equals(recovery.agentRunId())
                        && existing.attempt() == recovery.attempt());
        if (duplicate) {
            throw new IllegalStateException("Recovery attempt already exists");
        }
        recoveries.put(recovery.id(), recovery);
    }

    @Override
    public void saveHandoff(Handoff handoff) {
        handoffs.put(handoff.id(), handoff);
    }

    @Override
    public synchronized RunEvent appendEvent(
            String agentRunId,
            RunEventType type,
            String payload,
            ExecutionCursor cursor,
            Instant createdAt) {
        long sequence = events.values().stream()
                .filter(event -> agentRunId.equals(event.agentRunId()))
                .mapToLong(RunEvent::sequence)
                .max()
                .orElse(-1L) + 1L;
        RunEvent event = new RunEvent(
                agentRunId + ":event:" + sequence,
                agentRunId,
                sequence,
                type,
                payload,
                cursor,
                createdAt);
        events.put(event.id(), event);
        return event;
    }

    @Override
    public Optional<RunWorkerLease> findWorkerLease(String agentRunId) {
        return Optional.ofNullable(leases.get(agentRunId));
    }

    @Override
    public synchronized RunWorkerLeaseClaim claimWorkerLease(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            int leaseSeconds) {
        AgentRun run = runs.get(agentRunId);
        if (run == null || terminal(run.state())) {
            throw new IllegalStateException("AgentRun is terminal or missing: " + agentRunId);
        }
        Instant now = timeProvider.now();
        RunWorkerLease current = leases.get(agentRunId);
        if (current != null && current.activeAt(now)) {
            return new RunWorkerLeaseClaim(
                    RunWorkerLeaseClaimType.BUSY, current, "Run worker lease is active");
        }
        long fence = current == null ? 1 : current.fencingToken() + 1;
        long revision = current == null ? 1 : current.revision() + 1;
        RunWorkerLease acquired = new RunWorkerLease(
                agentRunId,
                leaseToken,
                leaseOwner,
                fence,
                now.plus(leaseSeconds, ChronoUnit.SECONDS),
                revision,
                now,
                now,
                null);
        leases.put(agentRunId, acquired);
        return new RunWorkerLeaseClaim(RunWorkerLeaseClaimType.ACQUIRED, acquired, null);
    }

    @Override
    public synchronized Optional<RunWorkerLease> heartbeatWorkerLease(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            int leaseSeconds) {
        Instant now = timeProvider.now();
        RunWorkerLease current = leases.get(agentRunId);
        if (!matches(current, leaseOwner, leaseToken, fencingToken, now)) {
            return Optional.empty();
        }
        RunWorkerLease renewed = new RunWorkerLease(
                current.agentRunId(), current.leaseToken(), current.leaseOwner(),
                current.fencingToken(), now.plus(leaseSeconds, ChronoUnit.SECONDS),
                current.revision() + 1, current.acquiredAt(), now, null);
        leases.put(agentRunId, renewed);
        return Optional.of(renewed);
    }

    @Override
    public synchronized boolean releaseWorkerLease(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            long fencingToken) {
        Instant now = timeProvider.now();
        RunWorkerLease current = leases.get(agentRunId);
        if (!matches(current, leaseOwner, leaseToken, fencingToken, now)) {
            return false;
        }
        leases.put(agentRunId, new RunWorkerLease(
                current.agentRunId(), current.leaseToken(), current.leaseOwner(),
                current.fencingToken(), now, current.revision() + 1,
                current.acquiredAt(), current.heartbeatAt(), now));
        return true;
    }

    @Override
    public synchronized boolean workerLeaseIsActive(
            String agentRunId,
            String leaseToken,
            long fencingToken) {
        RunWorkerLease current = leases.get(agentRunId);
        return current != null
                && current.leaseToken().equals(leaseToken)
                && current.fencingToken() == fencingToken
                && current.activeAt(timeProvider.now());
    }

    @Override
    public synchronized RuntimeContinuation saveContinuationIfAbsent(
            RuntimeContinuation continuation) {
        AgentRun run = runs.get(continuation.agentRunId());
        if (run == null || terminal(run.state())) {
            throw new IllegalStateException(
                    "AgentRun is terminal or missing: " + continuation.agentRunId());
        }
        Optional<RuntimeContinuation> existing = continuations.values().stream()
                .filter(value -> value.agentRunId().equals(continuation.agentRunId()))
                .filter(value -> value.deduplicationKey().equals(continuation.deduplicationKey()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        continuations.put(continuation.id(), continuation);
        return continuation;
    }

    @Override
    public Optional<RuntimeContinuation> findContinuationById(String continuationId) {
        return Optional.ofNullable(continuations.get(continuationId));
    }

    @Override
    public List<RuntimeContinuation> findContinuationsByRunId(String agentRunId) {
        return continuations.values().stream()
                .filter(value -> value.agentRunId().equals(agentRunId))
                .sorted(Comparator.comparing(RuntimeContinuation::createdAt)
                        .thenComparing(RuntimeContinuation::id))
                .toList();
    }

    @Override
    public synchronized Optional<RuntimeContinuationClaim> claimNextContinuation(
            String leaseOwner,
            String leaseToken,
            int leaseSeconds) {
        Instant now = timeProvider.now();
        continuations.values().stream()
                .filter(value -> value.state() == RuntimeContinuationState.CLAIMED)
                .filter(value -> value.leaseUntil() != null && !value.leaseUntil().isAfter(now))
                .filter(value -> value.attempt() >= value.maxAttempts())
                .toList()
                .forEach(value -> continuations.put(value.id(), terminalContinuation(
                        value, RuntimeContinuationState.FAILED,
                        "Continuation lease expired after maximum attempts", now)));
        List<RuntimeContinuation> candidates = continuations.values().stream()
                .filter(value -> (value.state() == RuntimeContinuationState.PENDING
                                && !value.availableAt().isAfter(now))
                        || (value.state() == RuntimeContinuationState.CLAIMED
                                && value.leaseUntil() != null
                                && !value.leaseUntil().isAfter(now)))
                .filter(value -> value.attempt() < value.maxAttempts())
                .filter(value -> runs.containsKey(value.agentRunId()))
                .filter(value -> !terminal(runs.get(value.agentRunId()).state()))
                .sorted(Comparator.comparing(RuntimeContinuation::availableAt)
                        .thenComparing(RuntimeContinuation::createdAt)
                        .thenComparing(RuntimeContinuation::id))
                .toList();
        for (RuntimeContinuation candidate : candidates) {
            RunWorkerLeaseClaim lease = claimWorkerLease(
                    candidate.agentRunId(), leaseOwner, leaseToken, leaseSeconds);
            if (lease.type() != RunWorkerLeaseClaimType.ACQUIRED) {
                continue;
            }
            RuntimeContinuation claimed = new RuntimeContinuation(
                    candidate.id(), candidate.agentRunId(), candidate.type(),
                    candidate.deduplicationKey(), candidate.payload(),
                    RuntimeContinuationState.CLAIMED, candidate.availableAt(),
                    candidate.attempt() + 1, candidate.maxAttempts(), leaseToken,
                    leaseOwner, lease.lease().fencingToken(), lease.lease().leaseUntil(),
                    candidate.revision() + 1, candidate.lastError(), candidate.createdAt(),
                    now, null);
            continuations.put(claimed.id(), claimed);
            return Optional.of(new RuntimeContinuationClaim(claimed, lease.lease()));
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<RuntimeContinuationClaim> heartbeatContinuation(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken,
            int leaseSeconds) {
        RuntimeContinuation current = continuations.get(continuationId);
        if (!claimedBy(current, leaseOwner, claimToken, fencingToken)) {
            return Optional.empty();
        }
        Optional<RunWorkerLease> lease = heartbeatWorkerLease(
                current.agentRunId(), leaseOwner, claimToken, fencingToken, leaseSeconds);
        if (lease.isEmpty()) {
            return Optional.empty();
        }
        Instant now = timeProvider.now();
        RuntimeContinuation renewed = new RuntimeContinuation(
                current.id(), current.agentRunId(), current.type(), current.deduplicationKey(),
                current.payload(), current.state(), current.availableAt(), current.attempt(),
                current.maxAttempts(), current.claimToken(), current.claimOwner(),
                current.fencingToken(), lease.get().leaseUntil(), current.revision() + 1,
                current.lastError(), current.createdAt(), now, null);
        continuations.put(renewed.id(), renewed);
        return Optional.of(new RuntimeContinuationClaim(renewed, lease.get()));
    }

    @Override
    public synchronized Optional<RuntimeContinuation> completeContinuation(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken) {
        RuntimeContinuation current = continuations.get(continuationId);
        if (!claimedBy(current, leaseOwner, claimToken, fencingToken)
                || !workerLeaseIsActive(current.agentRunId(), claimToken, fencingToken)) {
            return Optional.empty();
        }
        Instant now = timeProvider.now();
        RuntimeContinuation completed = terminalContinuation(
                current, RuntimeContinuationState.COMPLETED, current.lastError(), now);
        continuations.put(completed.id(), completed);
        releaseWorkerLease(current.agentRunId(), leaseOwner, claimToken, fencingToken);
        return Optional.of(completed);
    }

    @Override
    public synchronized Optional<RuntimeContinuation> failContinuation(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken,
            String error,
            int retryDelaySeconds) {
        RuntimeContinuation current = continuations.get(continuationId);
        if (!claimedBy(current, leaseOwner, claimToken, fencingToken)
                || !workerLeaseIsActive(current.agentRunId(), claimToken, fencingToken)) {
            return Optional.empty();
        }
        Instant now = timeProvider.now();
        RuntimeContinuation failed;
        if (current.attempt() < current.maxAttempts()) {
            failed = new RuntimeContinuation(
                    current.id(), current.agentRunId(), current.type(),
                    current.deduplicationKey(), current.payload(),
                    RuntimeContinuationState.PENDING,
                    now.plus(retryDelaySeconds, ChronoUnit.SECONDS), current.attempt(),
                    current.maxAttempts(), null, null, null, null,
                    current.revision() + 1, error, current.createdAt(), now, null);
        } else {
            failed = terminalContinuation(current, RuntimeContinuationState.FAILED, error, now);
        }
        continuations.put(failed.id(), failed);
        releaseWorkerLease(current.agentRunId(), leaseOwner, claimToken, fencingToken);
        return Optional.of(failed);
    }

    @Override
    public synchronized int cancelPendingContinuations(String agentRunId, String reason) {
        Instant now = timeProvider.now();
        int count = 0;
        for (RuntimeContinuation current : findContinuationsByRunId(agentRunId)) {
            if (current.state() != RuntimeContinuationState.PENDING
                    && current.state() != RuntimeContinuationState.CLAIMED) {
                continue;
            }
            continuations.put(current.id(), terminalContinuation(
                    current, RuntimeContinuationState.CANCELLED, reason, now));
            count++;
        }
        return count;
    }

    private RuntimeContinuation terminalContinuation(
            RuntimeContinuation current,
            RuntimeContinuationState state,
            String error,
            Instant now) {
        return new RuntimeContinuation(
                current.id(), current.agentRunId(), current.type(),
                current.deduplicationKey(), current.payload(), state, current.availableAt(),
                current.attempt(), current.maxAttempts(), null, null, null, null,
                current.revision() + 1, error, current.createdAt(), now, now);
    }

    private boolean claimedBy(
            RuntimeContinuation continuation,
            String owner,
            String token,
            long fence) {
        return continuation != null
                && continuation.state() == RuntimeContinuationState.CLAIMED
                && owner.equals(continuation.claimOwner())
                && token.equals(continuation.claimToken())
                && continuation.fencingToken() != null
                && continuation.fencingToken() == fence;
    }

    private static boolean matches(
            RunWorkerLease lease,
            String owner,
            String token,
            long fence,
            Instant now) {
        return lease != null
                && owner.equals(lease.leaseOwner())
                && token.equals(lease.leaseToken())
                && lease.fencingToken() == fence
                && lease.activeAt(now);
    }

    private static void requireNextRevision(AgentRun expected, AgentRun updated) {
        if (!expected.id().equals(updated.id()) || updated.revision() != expected.revision() + 1) {
            throw new IllegalArgumentException("AgentRun CAS requires exactly one revision advance");
        }
    }

    private static boolean terminal(AgentRunState state) {
        return state == AgentRunState.COMPLETED
                || state == AgentRunState.FAILED
                || state == AgentRunState.CANCELLED;
    }
}
