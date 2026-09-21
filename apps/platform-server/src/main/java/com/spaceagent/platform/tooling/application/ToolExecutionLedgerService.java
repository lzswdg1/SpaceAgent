package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.api.ClaimToolExecutionCommand;
import com.spaceagent.platform.tooling.api.CompleteToolExecutionCommand;
import com.spaceagent.platform.tooling.api.MarkToolExecutionUnknownCommand;
import com.spaceagent.platform.tooling.api.ReconcileUnknownToolExecutionCommand;
import com.spaceagent.platform.tooling.api.ToolExecutionClaimView;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.api.ToolExecutionTransitionView;
import com.spaceagent.platform.tooling.domain.ToolClaimOwnerProvider;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecision;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionCompletionRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionLedger;
import com.spaceagent.platform.tooling.domain.ToolExecutionLedgerRepository;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationEvidence;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationRequest;
import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionResult;
import com.spaceagent.platform.tooling.domain.ToolExecutionUnknownRequest;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Atomic claim and fenced transition application boundary for tool execution.
 *
 * <p>The repository owns each short transaction. This service never spans a transaction
 * across an external tool call.
 */
@Service
public class ToolExecutionLedgerService implements ToolExecutionLedgerApplicationApi {

    private final ToolExecutionLedgerRepository repository;
    private final IdGenerator idGenerator;
    private final ToolClaimOwnerProvider claimOwnerProvider;

    @Autowired
    public ToolExecutionLedgerService(
            ToolExecutionLedgerRepository repository,
            IdGenerator idGenerator,
            ToolClaimOwnerProvider claimOwnerProvider) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.claimOwnerProvider = claimOwnerProvider;
    }

    /**
     * Backward-compatible test constructor. The supplied TimeProvider is now owned by
     * the in-memory repository; PostgreSQL uses database time for leases.
     */
    public ToolExecutionLedgerService(
            ToolExecutionLedgerRepository repository,
            IdGenerator idGenerator,
            TimeProvider ignoredTimeProvider) {
        this(repository, idGenerator, generatedOwnerProvider());
    }

    @Override
    public ToolExecutionClaimView claim(ClaimToolExecutionCommand command) {
        String proposedClaimToken = UUID.randomUUID().toString();
        ToolExecutionClaimDecision decision = repository.claim(new ToolExecutionClaimRequest(
                idGenerator.nextId(),
                command.agentRunId(),
                command.runStepId(),
                command.toolName(),
                command.toolCallId(),
                command.idempotencyKey(),
                command.arguments(),
                command.inputHash(),
                proposedClaimToken,
                claimOwnerProvider.ownerId(),
                command.leaseSeconds()));
        ToolExecutionLedger ledger = decision.ledger();
        return new ToolExecutionClaimView(
                decision.type(),
                toView(ledger),
                decision.type() == com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecisionType.CLAIMED
                        ? ledger.claimToken()
                        : null,
                ledger.revision(),
                ledger.leaseUntil(),
                decision.expectedInputHash(),
                decision.actualInputHash(),
                decision.reason());
    }

    @Override
    public ToolExecutionTransitionView markUnknown(MarkToolExecutionUnknownCommand command) {
        ToolExecutionTransitionResult result = repository.markUnknown(new ToolExecutionUnknownRequest(
                command.agentRunId(),
                command.toolCallId(),
                command.claimToken(),
                command.expectedRevision(),
                command.reason(),
                new ToolExecutionReconciliationEvidence(command.reason(), null, Map.of())));
        return toView(result);
    }

    @Override
    public ToolExecutionTransitionView complete(CompleteToolExecutionCommand command) {
        ToolExecutionTransitionResult result = repository.complete(new ToolExecutionCompletionRequest(
                command.agentRunId(),
                command.toolCallId(),
                command.claimToken(),
                command.expectedRevision(),
                command.status(),
                command.result(),
                command.resultRef(),
                command.error(),
                lateCompletionEvidence(command)));
        return toView(result);
    }

    @Override
    public ToolExecutionTransitionView reconcileUnknown(ReconcileUnknownToolExecutionCommand command) {
        ToolExecutionTransitionResult result = repository.reconcileUnknown(
                new ToolExecutionReconciliationRequest(
                        command.agentRunId(),
                        command.toolCallId(),
                        command.inputHash(),
                        command.expectedRevision(),
                        command.resolution(),
                        command.result(),
                        command.resultRef(),
                        command.error(),
                        command.evidence(),
                        command.resolvedBy(),
                        command.resolutionReason()));
        return toView(result);
    }

    @Override
    public Optional<ToolExecutionLedgerView> findById(String id) {
        return repository.findById(id).map(ToolExecutionLedgerService::toView);
    }

    @Override
    public List<ToolExecutionLedgerView> findByRunId(String agentRunId) {
        return repository.findByRunId(agentRunId).stream()
                .sorted(Comparator.comparing(ToolExecutionLedger::startedAt))
                .map(ToolExecutionLedgerService::toView)
                .toList();
    }

    private ToolExecutionReconciliationEvidence lateCompletionEvidence(
            CompleteToolExecutionCommand command) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("requestedStatus", command.status().name());
        putIfPresent(details, "result", command.result());
        putIfPresent(details, "resultRef", command.resultRef());
        putIfPresent(details, "error", command.error());
        return new ToolExecutionReconciliationEvidence(
                "worker returned after the ordinary fenced completion window",
                command.resultRef(),
                details);
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static ToolClaimOwnerProvider generatedOwnerProvider() {
        String owner = "test-jvm-" + UUID.randomUUID();
        return () -> owner;
    }

    private static ToolExecutionTransitionView toView(ToolExecutionTransitionResult result) {
        return new ToolExecutionTransitionView(result.type(), toView(result.ledger()));
    }

    private static ToolExecutionLedgerView toView(ToolExecutionLedger entry) {
        return new ToolExecutionLedgerView(
                entry.id(),
                entry.agentRunId(),
                entry.runStepId(),
                entry.toolName(),
                entry.toolCallId(),
                entry.idempotencyKey(),
                entry.arguments(),
                entry.inputHash(),
                entry.status(),
                entry.result(),
                entry.resultRef(),
                entry.error(),
                entry.startedAt(),
                entry.completedAt(),
                entry.claimOwner(),
                entry.leaseUntil(),
                entry.revision(),
                entry.claimedAt(),
                entry.updatedAt(),
                entry.resolvedAt(),
                entry.resolvedBy(),
                entry.resolutionReason());
    }
}
