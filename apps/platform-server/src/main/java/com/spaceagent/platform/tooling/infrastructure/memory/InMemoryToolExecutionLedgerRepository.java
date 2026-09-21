package com.spaceagent.platform.tooling.infrastructure.memory;

import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecision;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecisionType;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionCompletionRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionLedger;
import com.spaceagent.platform.tooling.domain.ToolExecutionLedgerRepository;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationEvidence;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionResult;
import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionType;
import com.spaceagent.platform.tooling.domain.ToolExecutionUnknownRequest;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory semantic equivalent of the atomic PostgreSQL ledger for tests and local use.
 * Production concurrency guarantees come from PostgreSQL, not this JVM-local adapter.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryToolExecutionLedgerRepository implements ToolExecutionLedgerRepository {

    private final Map<String, ToolExecutionLedger> entries = new ConcurrentHashMap<>();
    private final TimeProvider timeProvider;

    public InMemoryToolExecutionLedgerRepository() {
        this(Instant::now);
    }

    public InMemoryToolExecutionLedgerRepository(TimeProvider timeProvider) {
        this.timeProvider = timeProvider;
    }

    @Override
    public Optional<ToolExecutionLedger> findById(String id) {
        return entries.values().stream().filter(entry -> id.equals(entry.id())).findFirst();
    }

    @Override
    public Optional<ToolExecutionLedger> findByRunIdAndToolCallId(
            String agentRunId,
            String toolCallId) {
        return findLogical(agentRunId, toolCallId);
    }

    @Override
    public List<ToolExecutionLedger> findByRunId(String agentRunId) {
        return entries.values().stream()
                .filter(entry -> agentRunId.equals(entry.agentRunId()))
                .toList();
    }

    @Override
    public ToolExecutionClaimDecision claim(ToolExecutionClaimRequest request) {
        AtomicReference<ToolExecutionClaimDecision> result = new AtomicReference<>();
        entries.compute(logicalKey(request.agentRunId(), request.toolCallId()), (key, existing) -> {
            if (existing == null) {
                Instant now = timeProvider.now();
                ToolExecutionLedger claimed = new ToolExecutionLedger(
                        request.id(), request.agentRunId(), request.runStepId(), request.toolName(),
                        request.toolCallId(), request.idempotencyKey(), request.arguments(), request.inputHash(),
                        ToolExecutionStatus.RUNNING, null, null, null, now, null,
                        request.claimToken(), request.claimOwner(), now.plusSeconds(request.leaseSeconds()),
                        1L, now, now, null, null, null, null);
                result.set(decision(ToolExecutionClaimDecisionType.CLAIMED, claimed, null));
                return claimed;
            }
            if (!request.inputHash().equals(existing.inputHash())
                    || idempotencyKeyConflicts(request.idempotencyKey(), existing.idempotencyKey())) {
                result.set(new ToolExecutionClaimDecision(
                        ToolExecutionClaimDecisionType.CONFLICT,
                        existing,
                        existing.inputHash(),
                        request.inputHash(),
                        "logical tool call input does not match the persisted claim"));
                return existing;
            }
            if (isTerminal(existing.status())) {
                result.set(decision(ToolExecutionClaimDecisionType.REPLAY, existing, null));
                return existing;
            }
            if (existing.status() == ToolExecutionStatus.UNKNOWN) {
                result.set(decision(ToolExecutionClaimDecisionType.UNKNOWN, existing, existing.error()));
                return existing;
            }
            Instant now = timeProvider.now();
            if (existing.status() == ToolExecutionStatus.RUNNING
                    && existing.leaseUntil() != null
                    && now.isBefore(existing.leaseUntil())) {
                result.set(decision(ToolExecutionClaimDecisionType.BUSY, existing, "tool claim lease is active"));
                return existing;
            }
            ToolExecutionLedger unknown = toUnknown(
                    existing,
                    "tool claim lease expired or legacy non-terminal state is ambiguous",
                    new ToolExecutionReconciliationEvidence(
                            "claim lease expired or legacy non-terminal state observed",
                            null,
                            Map.of("previousStatus", existing.status().name())),
                    now);
            result.set(decision(ToolExecutionClaimDecisionType.UNKNOWN, unknown, unknown.error()));
            return unknown;
        });
        return result.get();
    }

    @Override
    public ToolExecutionTransitionResult complete(ToolExecutionCompletionRequest request) {
        AtomicReference<ToolExecutionTransitionResult> result = new AtomicReference<>();
        entries.compute(logicalKey(request.agentRunId(), request.toolCallId()), (key, current) -> {
            current = requireCurrent(current);
            if (isTerminal(current.status())) {
                result.set(transition(ToolExecutionTransitionType.CURRENT_TERMINAL, current));
                return current;
            }
            if (current.status() == ToolExecutionStatus.UNKNOWN) {
                result.set(transition(ToolExecutionTransitionType.CURRENT_UNKNOWN, current));
                return current;
            }
            Instant now = timeProvider.now();
            if (current.status() == ToolExecutionStatus.RUNNING
                    && current.leaseUntil() != null
                    && !now.isBefore(current.leaseUntil())) {
                ToolExecutionLedger unknown = toUnknown(
                        current,
                        "tool result arrived after the claim lease expired",
                        request.lateEvidence(),
                        now);
                result.set(transition(ToolExecutionTransitionType.CURRENT_UNKNOWN, unknown));
                return unknown;
            }
            if (current.status() != ToolExecutionStatus.RUNNING
                    || !Objects.equals(request.claimToken(), current.claimToken())
                    || request.expectedRevision() != current.revision()) {
                result.set(transition(ToolExecutionTransitionType.CLAIM_LOST, current));
                return current;
            }
            ToolExecutionLedger completed = new ToolExecutionLedger(
                    current.id(), current.agentRunId(), current.runStepId(), current.toolName(),
                    current.toolCallId(), current.idempotencyKey(), current.arguments(), current.inputHash(),
                    request.status(), request.result(), request.resultRef(), request.error(),
                    current.startedAt(), now, current.claimToken(), current.claimOwner(), current.leaseUntil(),
                    current.revision() + 1, current.claimedAt(), now, current.reconciliationEvidence(),
                    current.resolvedAt(), current.resolvedBy(), current.resolutionReason());
            result.set(transition(ToolExecutionTransitionType.APPLIED, completed));
            return completed;
        });
        return result.get();
    }

    @Override
    public ToolExecutionTransitionResult markUnknown(ToolExecutionUnknownRequest request) {
        AtomicReference<ToolExecutionTransitionResult> result = new AtomicReference<>();
        entries.compute(logicalKey(request.agentRunId(), request.toolCallId()), (key, current) -> {
            current = requireCurrent(current);
            if (isTerminal(current.status())) {
                result.set(transition(ToolExecutionTransitionType.CURRENT_TERMINAL, current));
                return current;
            }
            if (current.status() == ToolExecutionStatus.UNKNOWN) {
                result.set(transition(ToolExecutionTransitionType.CURRENT_UNKNOWN, current));
                return current;
            }
            if (current.status() != ToolExecutionStatus.RUNNING
                    || !Objects.equals(request.claimToken(), current.claimToken())
                    || request.expectedRevision() != current.revision()) {
                result.set(transition(ToolExecutionTransitionType.CLAIM_LOST, current));
                return current;
            }
            ToolExecutionLedger unknown = toUnknown(
                    current, request.reason(), request.evidence(), timeProvider.now());
            result.set(transition(ToolExecutionTransitionType.APPLIED, unknown));
            return unknown;
        });
        return result.get();
    }

    @Override
    public ToolExecutionTransitionResult reconcileUnknown(ToolExecutionReconciliationRequest request) {
        AtomicReference<ToolExecutionTransitionResult> result = new AtomicReference<>();
        entries.compute(logicalKey(request.agentRunId(), request.toolCallId()), (key, current) -> {
            current = requireCurrent(current);
            if (!request.inputHash().equals(current.inputHash())) {
                result.set(transition(ToolExecutionTransitionType.CONFLICT, current));
                return current;
            }
            if (isTerminal(current.status())) {
                result.set(transition(ToolExecutionTransitionType.CURRENT_TERMINAL, current));
                return current;
            }
            if (current.status() != ToolExecutionStatus.UNKNOWN
                    || request.expectedRevision() != current.revision()) {
                result.set(transition(ToolExecutionTransitionType.CLAIM_LOST, current));
                return current;
            }
            Instant now = timeProvider.now();
            ToolExecutionLedger resolved = new ToolExecutionLedger(
                    current.id(), current.agentRunId(), current.runStepId(), current.toolName(),
                    current.toolCallId(), current.idempotencyKey(), current.arguments(), current.inputHash(),
                    request.resolution(), request.result(), request.resultRef(), request.error(),
                    current.startedAt(), now, null, current.claimOwner(), current.leaseUntil(),
                    current.revision() + 1, current.claimedAt(), now, request.evidence(),
                    now, request.resolvedBy(), request.resolutionReason());
            result.set(transition(ToolExecutionTransitionType.APPLIED, resolved));
            return resolved;
        });
        return result.get();
    }

    private Optional<ToolExecutionLedger> findLogical(String agentRunId, String toolCallId) {
        return Optional.ofNullable(entries.get(logicalKey(agentRunId, toolCallId)));
    }

    private ToolExecutionLedger requireCurrent(ToolExecutionLedger current) {
        if (current == null) {
            throw new IllegalStateException("Tool execution ledger entry not found");
        }
        return current;
    }

    private String logicalKey(String agentRunId, String toolCallId) {
        return agentRunId + "\u0000" + toolCallId;
    }

    private ToolExecutionLedger toUnknown(
            ToolExecutionLedger current,
            String reason,
            ToolExecutionReconciliationEvidence evidence,
            Instant now) {
        return new ToolExecutionLedger(
                current.id(), current.agentRunId(), current.runStepId(), current.toolName(),
                current.toolCallId(), current.idempotencyKey(), current.arguments(), current.inputHash(),
                ToolExecutionStatus.UNKNOWN, current.result(), current.resultRef(), reason,
                current.startedAt(), current.completedAt(), null, current.claimOwner(), current.leaseUntil(),
                current.revision() + 1, current.claimedAt(), now, evidence,
                current.resolvedAt(), current.resolvedBy(), current.resolutionReason());
    }

    private static ToolExecutionClaimDecision decision(
            ToolExecutionClaimDecisionType type,
            ToolExecutionLedger ledger,
            String reason) {
        return new ToolExecutionClaimDecision(type, ledger, null, null, reason);
    }

    private static ToolExecutionTransitionResult transition(
            ToolExecutionTransitionType type,
            ToolExecutionLedger ledger) {
        return new ToolExecutionTransitionResult(type, ledger);
    }

    private static boolean idempotencyKeyConflicts(String requested, String existing) {
        return requested != null && existing != null && !requested.equals(existing);
    }

    private static boolean isTerminal(ToolExecutionStatus status) {
        return status == ToolExecutionStatus.SUCCEEDED
                || status == ToolExecutionStatus.FAILED
                || status == ToolExecutionStatus.TIMED_OUT
                || status == ToolExecutionStatus.CANCELLED;
    }
}
