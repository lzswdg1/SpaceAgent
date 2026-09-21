package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRun;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.domain.RunWorkerLease;
import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaim;
import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaimType;
import com.spaceagent.platform.runtime.domain.RuntimeContinuation;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationClaim;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationState;
import com.spaceagent.platform.runtime.domain.RuntimeLedgerRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class RuntimeCoordinationApplicationService
        implements RuntimeCoordinationApplicationApi {

    private final RuntimeLedgerRepository repository;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final ObjectMapper objectMapper;

    public RuntimeCoordinationApplicationService(
            RuntimeLedgerRepository repository,
            IdGenerator ids,
            TimeProvider time,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.ids = ids;
        this.time = time;
        this.objectMapper = objectMapper;
    }

    @Override
    public LeaseClaimView acquireLease(AcquireLeaseCommand command) {
        requireOwner(command.leaseOwner());
        requireLeaseSeconds(command.leaseSeconds());
        AgentRun run = requireNonTerminalRun(command.agentRunId());
        RunWorkerLeaseClaim claim;
        try {
            claim = repository.claimWorkerLease(
                    run.id(), command.leaseOwner(), ids.nextId(), command.leaseSeconds());
        } catch (IllegalStateException race) {
            throw coordinationConflict("Agent run became terminal while acquiring lease", race);
        }
        if (claim.type() == RunWorkerLeaseClaimType.ACQUIRED) {
            appendEvent(run, RunEventType.WORKER_LEASE_ACQUIRED, Map.of(
                    "leaseOwner", claim.lease().leaseOwner(),
                    "fencingToken", claim.lease().fencingToken(),
                    "leaseUntil", claim.lease().leaseUntil().toString()));
        }
        return new LeaseClaimView(claim.type(), view(claim.lease()), claim.reason());
    }

    @Override
    public LeaseView heartbeatLease(HeartbeatLeaseCommand command) {
        requireOwner(command.leaseOwner());
        requireLeaseSeconds(command.leaseSeconds());
        return repository.heartbeatWorkerLease(
                        command.agentRunId(), command.leaseOwner(), command.leaseToken(),
                        command.fencingToken(), command.leaseSeconds())
                .map(RuntimeCoordinationApplicationService::view)
                .orElseThrow(RuntimeCoordinationApplicationService::staleClaim);
    }

    @Override
    public void releaseLease(ReleaseLeaseCommand command) {
        requireOwner(command.leaseOwner());
        AgentRun run = requireRun(command.agentRunId());
        if (!repository.releaseWorkerLease(
                command.agentRunId(), command.leaseOwner(), command.leaseToken(),
                command.fencingToken())) {
            throw staleClaim();
        }
        appendEvent(run, RunEventType.WORKER_LEASE_RELEASED, Map.of(
                "leaseOwner", command.leaseOwner(),
                "fencingToken", command.fencingToken()));
    }

    @Override
    public ContinuationView enqueue(EnqueueContinuationCommand command) {
        AgentRun run = requireNonTerminalRun(command.agentRunId());
        if (command.type() == null
                || command.deduplicationKey() == null
                || command.deduplicationKey().isBlank()
                || command.deduplicationKey().length() > 200
                || command.maxAttempts() < 1
                || command.maxAttempts() > 20) {
            throw new IllegalArgumentException("invalid Runtime continuation command");
        }
        String payload = validatePayload(command.payload());
        Instant now = time.now();
        RuntimeContinuation proposed = new RuntimeContinuation(
                command.requestedId() == null ? ids.nextId() : command.requestedId(),
                run.id(), command.type(), command.deduplicationKey(), payload,
                RuntimeContinuationState.PENDING,
                command.availableAt() == null ? now : command.availableAt(),
                0, command.maxAttempts(), null, null, null, null, 0, null,
                now, now, null);
        RuntimeContinuation saved;
        try {
            saved = repository.saveContinuationIfAbsent(proposed);
        } catch (IllegalStateException race) {
            throw coordinationConflict("Agent run became terminal while enqueueing", race);
        }
        if (saved.id().equals(proposed.id())) {
            appendEvent(run, RunEventType.CONTINUATION_ENQUEUED, Map.of(
                    "continuationId", saved.id(),
                    "type", saved.type().name(),
                    "deduplicationKey", saved.deduplicationKey()));
        }
        return view(saved);
    }

    @Override
    public Optional<ContinuationClaimView> claimNext(ClaimNextContinuationCommand command) {
        requireOwner(command.leaseOwner());
        requireLeaseSeconds(command.leaseSeconds());
        Optional<RuntimeContinuationClaim> claimed = repository.claimNextContinuation(
                command.leaseOwner(), ids.nextId(), command.leaseSeconds());
        claimed.ifPresent(value -> appendEvent(
                requireRun(value.continuation().agentRunId()),
                RunEventType.CONTINUATION_CLAIMED,
                Map.of(
                        "continuationId", value.continuation().id(),
                        "leaseOwner", value.lease().leaseOwner(),
                        "attempt", value.continuation().attempt(),
                        "fencingToken", value.lease().fencingToken())));
        return claimed.map(RuntimeCoordinationApplicationService::view);
    }

    @Override
    public ContinuationClaimView heartbeat(HeartbeatContinuationCommand command) {
        requireOwner(command.leaseOwner());
        requireLeaseSeconds(command.leaseSeconds());
        return repository.heartbeatContinuation(
                        command.continuationId(), command.leaseOwner(), command.claimToken(),
                        command.fencingToken(), command.leaseSeconds())
                .map(RuntimeCoordinationApplicationService::view)
                .orElseThrow(RuntimeCoordinationApplicationService::staleClaim);
    }

    @Override
    public ContinuationView complete(CompleteContinuationCommand command) {
        requireOwner(command.leaseOwner());
        RuntimeContinuation completed = repository.completeContinuation(
                        command.continuationId(), command.leaseOwner(), command.claimToken(),
                        command.fencingToken())
                .orElseThrow(RuntimeCoordinationApplicationService::staleClaim);
        AgentRun run = requireRun(completed.agentRunId());
        if (run.state() == AgentRunState.COMPLETED
                || run.state() == AgentRunState.FAILED
                || run.state() == AgentRunState.CANCELLED) {
            repository.cancelPendingContinuations(
                    run.id(), "AgentRun became " + run.state().name());
        }
        appendEvent(run, RunEventType.CONTINUATION_COMPLETED,
                Map.of("continuationId", completed.id(), "attempt", completed.attempt()));
        return view(completed);
    }

    @Override
    public ContinuationView fail(FailContinuationCommand command) {
        requireOwner(command.leaseOwner());
        if (command.error() == null || command.error().isBlank()
                || command.retryDelaySeconds() < 0 || command.retryDelaySeconds() > 3600) {
            throw new IllegalArgumentException("continuation error and retry delay are invalid");
        }
        RuntimeContinuation failed = repository.failContinuation(
                        command.continuationId(), command.leaseOwner(), command.claimToken(),
                        command.fencingToken(), command.error(), command.retryDelaySeconds())
                .orElseThrow(RuntimeCoordinationApplicationService::staleClaim);
        appendEvent(requireRun(failed.agentRunId()), RunEventType.CONTINUATION_FAILED, Map.of(
                "continuationId", failed.id(),
                "attempt", failed.attempt(),
                "state", failed.state().name()));
        return view(failed);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContinuationView> find(String continuationId) {
        return repository.findContinuationById(continuationId)
                .map(RuntimeCoordinationApplicationService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContinuationView> findByRun(String agentRunId) {
        requireRun(agentRunId);
        return repository.findContinuationsByRunId(agentRunId).stream()
                .map(RuntimeCoordinationApplicationService::view)
                .toList();
    }

    private AgentRun requireRun(String agentRunId) {
        return repository.findRunById(agentRunId)
                .orElseThrow(() -> new BusinessException(
                        "Agent run not found: " + agentRunId, HttpStatus.NOT_FOUND));
    }

    private AgentRun requireNonTerminalRun(String agentRunId) {
        AgentRun run = requireRun(agentRunId);
        if (run.state() == AgentRunState.COMPLETED
                || run.state() == AgentRunState.FAILED
                || run.state() == AgentRunState.CANCELLED) {
            throw new BusinessException("Agent run is terminal", HttpStatus.CONFLICT);
        }
        return run;
    }

    private String validatePayload(String payload) {
        String value = payload == null || payload.isBlank() ? "{}" : payload;
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Continuation payload must be a JSON object");
            }
            return objectMapper.writeValueAsString(node);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Continuation payload must be valid JSON", error);
        }
    }

    private void appendEvent(AgentRun run, RunEventType type, Map<String, ?> payload) {
        try {
            repository.appendEvent(
                    run.id(), type, objectMapper.writeValueAsString(payload),
                    run.executionCursor(), time.now());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to append Runtime coordination event", error);
        }
    }

    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 160) {
            throw new IllegalArgumentException("leaseOwner is required and bounded");
        }
    }

    private static void requireLeaseSeconds(int seconds) {
        if (seconds < 1 || seconds > 300) {
            throw new IllegalArgumentException("leaseSeconds must be between 1 and 300");
        }
    }

    private static BusinessException staleClaim() {
        return new BusinessException(
                "Runtime lease/continuation claim is stale or expired",
                HttpStatus.CONFLICT,
                "RUNTIME_FENCE_REJECTED");
    }

    private static BusinessException coordinationConflict(String message, Exception cause) {
        return new BusinessException(
                message + ": " + cause.getMessage(),
                HttpStatus.CONFLICT,
                "RUNTIME_COORDINATION_CONFLICT");
    }

    private static LeaseView view(RunWorkerLease lease) {
        return new LeaseView(
                lease.agentRunId(), lease.leaseToken(), lease.leaseOwner(),
                lease.fencingToken(), lease.leaseUntil(), lease.revision(),
                lease.acquiredAt(), lease.heartbeatAt(), lease.releasedAt());
    }

    private static ContinuationView view(RuntimeContinuation continuation) {
        return new ContinuationView(
                continuation.id(), continuation.agentRunId(), continuation.type(),
                continuation.deduplicationKey(), continuation.payload(), continuation.state(),
                continuation.availableAt(), continuation.attempt(), continuation.maxAttempts(),
                continuation.claimToken(), continuation.claimOwner(), continuation.fencingToken(),
                continuation.leaseUntil(), continuation.revision(), continuation.lastError(),
                continuation.createdAt(), continuation.updatedAt(), continuation.completedAt());
    }

    private static ContinuationClaimView view(RuntimeContinuationClaim claim) {
        return new ContinuationClaimView(view(claim.continuation()), view(claim.lease()));
    }
}
