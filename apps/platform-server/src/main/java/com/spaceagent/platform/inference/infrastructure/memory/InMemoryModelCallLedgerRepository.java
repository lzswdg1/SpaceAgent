package com.spaceagent.platform.inference.infrastructure.memory;

import com.spaceagent.platform.inference.domain.ModelCallClaimDecision;
import com.spaceagent.platform.inference.domain.ModelCallClaimDecisionType;
import com.spaceagent.platform.inference.domain.ModelCallClaimRequest;
import com.spaceagent.platform.inference.domain.ModelCallCompletionRequest;
import com.spaceagent.platform.inference.domain.ModelCallFirstChunkRequest;
import com.spaceagent.platform.inference.domain.ModelCallLedger;
import com.spaceagent.platform.inference.domain.ModelCallLedgerRepository;
import com.spaceagent.platform.inference.domain.ModelCallStatus;
import com.spaceagent.platform.inference.domain.ModelCallTransitionResult;
import com.spaceagent.platform.inference.domain.ModelCallTransitionType;
import com.spaceagent.platform.inference.domain.ModelCallUnknownRequest;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JVM-local semantic equivalent used by the memory profile; PostgreSQL is authoritative. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryModelCallLedgerRepository implements ModelCallLedgerRepository {

    private final Map<String, ModelCallLedger> entries = new HashMap<>();
    private final TimeProvider timeProvider;

    public InMemoryModelCallLedgerRepository() {
        this(Instant::now);
    }

    public InMemoryModelCallLedgerRepository(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    @Override
    public synchronized ModelCallClaimDecision claim(ModelCallClaimRequest request) {
        String key = key(request.agentRunId(), request.logicalCallId());
        ModelCallLedger current = entries.get(key);
        if (current == null) {
            Instant now = timeProvider.now();
            ModelCallLedger claimed = new ModelCallLedger(
                    request.id(), request.agentRunId(), request.runStepId(), request.logicalCallId(),
                    request.requestHash(), ModelCallStatus.RUNNING, request.providerId(), request.modelId(),
                    request.claimToken(), request.claimOwner(), now.plusSeconds(request.leaseSeconds()),
                    1L, now, null, null, null, null, null, null, null, now, now);
            entries.put(key, claimed);
            return decision(ModelCallClaimDecisionType.CLAIMED, claimed, null);
        }
        if (!request.requestHash().equals(current.requestHash())) {
            return new ModelCallClaimDecision(
                    ModelCallClaimDecisionType.CONFLICT,
                    current,
                    current.requestHash(),
                    request.requestHash(),
                    "logical model call request does not match the persisted claim");
        }
        if (isTerminal(current.status())) {
            return decision(ModelCallClaimDecisionType.REPLAY, current, null);
        }
        if (current.status() == ModelCallStatus.UNKNOWN) {
            return decision(ModelCallClaimDecisionType.UNKNOWN, current, current.errorSummary());
        }
        Instant now = timeProvider.now();
        if (now.isBefore(current.leaseUntil())) {
            return decision(ModelCallClaimDecisionType.BUSY, current, "model call claim lease is active");
        }
        ModelCallLedger unknown = unknown(
                current,
                "MODEL_CALL_LEASE_EXPIRED",
                "model call lease expired; provider completion is ambiguous",
                now);
        entries.put(key, unknown);
        return decision(ModelCallClaimDecisionType.UNKNOWN, unknown, unknown.errorSummary());
    }

    @Override
    public synchronized ModelCallTransitionResult complete(ModelCallCompletionRequest request) {
        String key = key(request.agentRunId(), request.logicalCallId());
        ModelCallLedger current = required(entries.get(key));
        if (isTerminal(current.status())) {
            return transition(ModelCallTransitionType.CURRENT_TERMINAL, current);
        }
        if (current.status() == ModelCallStatus.UNKNOWN) {
            return transition(ModelCallTransitionType.CURRENT_UNKNOWN, current);
        }
        Instant now = timeProvider.now();
        if (!now.isBefore(current.leaseUntil())) {
            ModelCallLedger unknown = unknown(
                    current,
                    "MODEL_CALL_LATE_COMPLETION",
                    "model result arrived after the claim lease expired",
                    now);
            entries.put(key, unknown);
            return transition(ModelCallTransitionType.CURRENT_UNKNOWN, unknown);
        }
        if (!request.claimToken().equals(current.claimToken())
                || request.expectedRevision() != current.revision()) {
            return transition(ModelCallTransitionType.CLAIM_LOST, current);
        }
        ModelCallLedger completed = new ModelCallLedger(
                current.id(), current.agentRunId(), current.runStepId(), current.logicalCallId(),
                current.requestHash(), request.status(), current.providerId(), current.modelId(),
                current.claimToken(), current.claimOwner(), current.leaseUntil(), current.revision() + 1,
                current.claimedAt(), current.firstChunkAt(), current.firstChunkMillis(),
                request.providerRequestId(), request.responsePayload(),
                request.usagePayload(), request.errorCode(), request.errorSummary(),
                current.createdAt(), now);
        entries.put(key, completed);
        return transition(ModelCallTransitionType.APPLIED, completed);
    }

    @Override
    public synchronized ModelCallTransitionResult recordFirstChunk(ModelCallFirstChunkRequest request) {
        String key = key(request.agentRunId(), request.logicalCallId());
        ModelCallLedger current = required(entries.get(key));
        if (current.firstChunkAt() != null) {
            return transition(ModelCallTransitionType.APPLIED, current);
        }
        Instant now = timeProvider.now();
        if (current.status() != ModelCallStatus.RUNNING
                || !request.claimToken().equals(current.claimToken())
                || request.expectedRevision() != current.revision()
                || !now.isBefore(current.leaseUntil())) {
            return transition(isTerminal(current.status())
                    ? ModelCallTransitionType.CURRENT_TERMINAL
                    : current.status() == ModelCallStatus.UNKNOWN
                    ? ModelCallTransitionType.CURRENT_UNKNOWN
                    : ModelCallTransitionType.CLAIM_LOST, current);
        }
        long millis = request.elapsedMillis();
        ModelCallLedger updated = new ModelCallLedger(
                current.id(), current.agentRunId(), current.runStepId(), current.logicalCallId(),
                current.requestHash(), current.status(), current.providerId(), current.modelId(),
                current.claimToken(), current.claimOwner(), current.leaseUntil(), current.revision(),
                current.claimedAt(), now, millis, current.providerRequestId(),
                current.responsePayload(), current.usagePayload(), current.errorCode(),
                current.errorSummary(), current.createdAt(), now);
        entries.put(key, updated);
        return transition(ModelCallTransitionType.APPLIED, updated);
    }

    @Override
    public synchronized ModelCallTransitionResult markUnknown(ModelCallUnknownRequest request) {
        String key = key(request.agentRunId(), request.logicalCallId());
        ModelCallLedger current = required(entries.get(key));
        if (isTerminal(current.status())) {
            return transition(ModelCallTransitionType.CURRENT_TERMINAL, current);
        }
        if (current.status() == ModelCallStatus.UNKNOWN) {
            return transition(ModelCallTransitionType.CURRENT_UNKNOWN, current);
        }
        if (!request.claimToken().equals(current.claimToken())
                || request.expectedRevision() != current.revision()) {
            return transition(ModelCallTransitionType.CLAIM_LOST, current);
        }
        ModelCallLedger unknown = unknown(
                current, request.errorCode(), request.errorSummary(), timeProvider.now());
        entries.put(key, unknown);
        return transition(ModelCallTransitionType.APPLIED, unknown);
    }

    @Override
    public synchronized Optional<ModelCallLedger> findByRunIdAndLogicalCallId(
            String agentRunId,
            String logicalCallId) {
        return Optional.ofNullable(entries.get(key(agentRunId, logicalCallId)));
    }

    @Override
    public synchronized List<ModelCallLedger> findByRunId(String agentRunId) {
        return entries.values().stream()
                .filter(entry -> agentRunId.equals(entry.agentRunId()))
                .toList();
    }

    private static ModelCallLedger unknown(
            ModelCallLedger current,
            String errorCode,
            String errorSummary,
            Instant now) {
        return new ModelCallLedger(
                current.id(), current.agentRunId(), current.runStepId(), current.logicalCallId(),
                current.requestHash(), ModelCallStatus.UNKNOWN, current.providerId(), current.modelId(),
                null, current.claimOwner(), current.leaseUntil(), current.revision() + 1,
                current.claimedAt(), current.firstChunkAt(), current.firstChunkMillis(),
                current.providerRequestId(), current.responsePayload(),
                current.usagePayload(), errorCode, errorSummary, current.createdAt(), now);
    }

    private static boolean isTerminal(ModelCallStatus status) {
        return status == ModelCallStatus.SUCCEEDED
                || status == ModelCallStatus.FAILED
                || status == ModelCallStatus.TIMED_OUT
                || status == ModelCallStatus.CANCELLED;
    }

    private static String key(String runId, String logicalCallId) {
        return runId + '\u0000' + logicalCallId;
    }

    private static ModelCallLedger required(ModelCallLedger value) {
        if (value == null) {
            throw new IllegalStateException("model call ledger row not found");
        }
        return value;
    }

    private static ModelCallClaimDecision decision(
            ModelCallClaimDecisionType type,
            ModelCallLedger ledger,
            String reason) {
        return new ModelCallClaimDecision(type, ledger, null, null, reason);
    }

    private static ModelCallTransitionResult transition(
            ModelCallTransitionType type,
            ModelCallLedger ledger) {
        return new ModelCallTransitionResult(type, ledger);
    }
}
