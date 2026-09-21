package com.spaceagent.platform.inference.application;

import com.spaceagent.platform.inference.api.ClaimModelCallCommand;
import com.spaceagent.platform.inference.api.CompleteModelCallCommand;
import com.spaceagent.platform.inference.api.MarkModelCallUnknownCommand;
import com.spaceagent.platform.inference.api.ModelCallClaimView;
import com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi;
import com.spaceagent.platform.inference.api.ModelCallLedgerView;
import com.spaceagent.platform.inference.api.ModelCallTransitionView;
import com.spaceagent.platform.inference.api.RecordModelCallFirstChunkCommand;
import com.spaceagent.platform.inference.domain.ModelCallClaimDecision;
import com.spaceagent.platform.inference.domain.ModelCallClaimDecisionType;
import com.spaceagent.platform.inference.domain.ModelCallClaimOwnerProvider;
import com.spaceagent.platform.inference.domain.ModelCallClaimRequest;
import com.spaceagent.platform.inference.domain.ModelCallCompletionRequest;
import com.spaceagent.platform.inference.domain.ModelCallLedger;
import com.spaceagent.platform.inference.domain.ModelCallFirstChunkRequest;
import com.spaceagent.platform.inference.domain.ModelCallLedgerRepository;
import com.spaceagent.platform.inference.domain.ModelCallTransitionResult;
import com.spaceagent.platform.inference.domain.ModelCallUnknownRequest;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ModelCallLedgerService implements ModelCallLedgerApplicationApi {

    private final ModelCallLedgerRepository repository;
    private final IdGenerator idGenerator;
    private final ModelCallClaimOwnerProvider ownerProvider;

    public ModelCallLedgerService(
            ModelCallLedgerRepository repository,
            IdGenerator idGenerator,
            ModelCallClaimOwnerProvider ownerProvider) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.ownerProvider = ownerProvider;
    }

    @Override
    public ModelCallClaimView claim(ClaimModelCallCommand command) {
        ModelCallClaimDecision decision = repository.claim(new ModelCallClaimRequest(
                idGenerator.nextId(),
                command.agentRunId(),
                command.runStepId(),
                command.logicalCallId(),
                command.requestHash(),
                command.providerId(),
                command.modelId(),
                UUID.randomUUID().toString(),
                ownerProvider.ownerId(),
                command.leaseSeconds()));
        ModelCallLedger ledger = decision.ledger();
        return new ModelCallClaimView(
                decision.type(),
                toView(ledger),
                decision.type() == ModelCallClaimDecisionType.CLAIMED ? ledger.claimToken() : null,
                ledger.revision(),
                ledger.leaseUntil(),
                decision.expectedRequestHash(),
                decision.actualRequestHash(),
                decision.reason());
    }

    @Override
    public ModelCallTransitionView complete(CompleteModelCallCommand command) {
        return toView(repository.complete(new ModelCallCompletionRequest(
                command.agentRunId(),
                command.logicalCallId(),
                command.claimToken(),
                command.expectedRevision(),
                command.status(),
                command.providerRequestId(),
                command.responsePayload(),
                command.usagePayload(),
                command.errorCode(),
                command.errorSummary())));
    }

    @Override
    public ModelCallTransitionView recordFirstChunk(RecordModelCallFirstChunkCommand command) {
        return toView(repository.recordFirstChunk(new ModelCallFirstChunkRequest(
                command.agentRunId(), command.logicalCallId(), command.claimToken(),
                command.expectedRevision(), command.elapsedMillis())));
    }

    @Override
    public ModelCallTransitionView markUnknown(MarkModelCallUnknownCommand command) {
        return toView(repository.markUnknown(new ModelCallUnknownRequest(
                command.agentRunId(),
                command.logicalCallId(),
                command.claimToken(),
                command.expectedRevision(),
                command.errorCode(),
                command.errorSummary())));
    }

    @Override
    public List<ModelCallLedgerView> findByRunId(String agentRunId) {
        return repository.findByRunId(agentRunId).stream()
                .sorted(Comparator.comparing(ModelCallLedger::createdAt))
                .map(ModelCallLedgerService::toView)
                .toList();
    }

    @Override
    public Optional<ModelCallLedgerView> findByLogicalCall(String agentRunId, String logicalCallId) {
        return repository.findByRunIdAndLogicalCallId(agentRunId, logicalCallId)
                .map(ModelCallLedgerService::toView);
    }

    private static ModelCallTransitionView toView(ModelCallTransitionResult result) {
        return new ModelCallTransitionView(result.type(), toView(result.ledger()));
    }

    private static ModelCallLedgerView toView(ModelCallLedger ledger) {
        return new ModelCallLedgerView(
                ledger.id(), ledger.agentRunId(), ledger.runStepId(), ledger.logicalCallId(),
                ledger.requestHash(), ledger.status(), ledger.providerId(), ledger.modelId(),
                ledger.claimOwner(), ledger.leaseUntil(), ledger.revision(), ledger.claimedAt(),
                ledger.firstChunkAt(), ledger.firstChunkMillis(),
                ledger.providerRequestId(), ledger.responsePayload(), ledger.usagePayload(),
                ledger.errorCode(), ledger.errorSummary(), ledger.createdAt(), ledger.updatedAt());
    }
}
