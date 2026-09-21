package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.ReconcileUnknownToolExecutionCommand;
import com.spaceagent.platform.tooling.api.ToolEffectVerificationApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.domain.LocalToolEffectVerifierRegistry;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationEvidence;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionType;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ToolEffectVerificationApplicationService
        implements ToolEffectVerificationApplicationApi {
    private final ToolExecutionLedgerApplicationApi ledger;
    private final LocalToolEffectVerifierRegistry registry;
    private final ObjectMapper json;
    private final TimeProvider time;

    public ToolEffectVerificationApplicationService(
            ToolExecutionLedgerApplicationApi ledger,
            LocalToolEffectVerifierRegistry registry,
            ObjectMapper json,
            TimeProvider time) {
        this.ledger = ledger;
        this.registry = registry;
        this.json = json;
        this.time = time;
    }

    @Override
    public VerificationView verify(VerifyCommand command) {
        ToolExecutionLedgerView current = ledger.findByRunId(command.agentRunId()).stream()
                .filter(value -> value.toolCallId().equals(command.toolCallId()))
                .findFirst().orElseThrow(() -> business(
                        "Tool execution not found", "TOOL_EFFECT_VERIFIER_NOT_FOUND",
                        HttpStatus.NOT_FOUND));
        if (terminal(current) && current.resolvedAt() != null) return existing(current);
        if (current.status() != ToolExecutionStatus.UNKNOWN) {
            throw business("Tool execution is not UNKNOWN",
                    "TOOL_EFFECT_VERIFIER_STATE_CONFLICT", HttpStatus.CONFLICT);
        }
        if (current.revision() != command.expectedLedgerRevision()) throw revisionConflict();

        ToolEffectVerifier.Request request = new ToolEffectVerifier.Request(
                command.tenantId(), command.ownerUserId(), current.agentRunId(),
                current.runStepId(), current.id(), current.toolCallId(), current.toolName(),
                current.inputHash(), current.revision(), current.arguments(),
                ToolEffectVerifier.sha256(current.arguments()), command.scope(), time.now());
        ToolEffectVerifier.Evidence evidence;
        try {
            evidence = registry.verify(request);
        } catch (BusinessException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw business("Tool effect has no supported verifier",
                    "TOOL_EFFECT_VERIFIER_UNSUPPORTED", HttpStatus.CONFLICT);
        }
        if (evidence.verdict() == ToolEffectVerifier.Verdict.INCONCLUSIVE) {
            throw business("Tool effect could not be proven",
                    "TOOL_EFFECT_VERIFIER_INCONCLUSIVE", HttpStatus.CONFLICT);
        }
        ToolExecutionStatus resolution = evidence.verdict()
                == ToolEffectVerifier.Verdict.PROVEN_APPLIED
                ? ToolExecutionStatus.SUCCEEDED : ToolExecutionStatus.FAILED;
        var transition = ledger.reconcileUnknown(new ReconcileUnknownToolExecutionCommand(
                current.agentRunId(), current.toolCallId(), current.inputHash(),
                current.revision(), resolution, safeResult(evidence), null,
                resolution == ToolExecutionStatus.FAILED
                        ? "POSTCONDITION_NOT_APPLIED" : null,
                new ToolExecutionReconciliationEvidence(
                        "capability-specific read-only postcondition", null,
                        evidence(evidence)),
                command.ownerUserId(), command.reason()));
        if (transition.type() == ToolExecutionTransitionType.CURRENT_TERMINAL
                && transition.ledger().resolvedAt() != null) {
            return existing(transition.ledger());
        }
        if (transition.type() != ToolExecutionTransitionType.APPLIED) throw revisionConflict();
        return view(request, evidence, transition.type().name(), transition.ledger());
    }

    private VerificationView existing(ToolExecutionLedgerView current) {
        return new VerificationView(
                "existing_reconciliation", 1, "EXISTING", current.status().name(),
                "existing/v1", ToolEffectVerifier.sha256(current.inputHash()), null, 0,
                "ALREADY_RESOLVED", current.resolvedAt(), "CURRENT_TERMINAL",
                current.status().name(), current.revision(), current.resolvedAt());
    }

    private VerificationView view(
            ToolEffectVerifier.Request request,
            ToolEffectVerifier.Evidence evidence,
            String transition,
            ToolExecutionLedgerView resolved) {
        return new VerificationView(
                evidence.verifierId(), evidence.verifierVersion(),
                request.scope().effectKind().name(), evidence.verdict().name(),
                evidence.evidenceSchemaVersion(), evidence.subjectSha256(),
                evidence.observationSha256(), evidence.observedBytes(), evidence.safeCode(),
                evidence.completedAt(), transition, resolved.status().name(),
                resolved.revision(), resolved.resolvedAt());
    }

    private static Map<String, String> evidence(ToolEffectVerifier.Evidence value) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("verifierId", value.verifierId());
        result.put("verifierVersion", Integer.toString(value.verifierVersion()));
        result.put("evidenceSchemaVersion", value.evidenceSchemaVersion());
        result.put("verdict", value.verdict().name());
        result.put("subjectSha256", value.subjectSha256());
        if (value.observationSha256() != null) {
            result.put("observationSha256", value.observationSha256());
        }
        result.put("observedBytes", Integer.toString(value.observedBytes()));
        result.put("safeCode", value.safeCode());
        return Map.copyOf(result);
    }

    private String safeResult(ToolEffectVerifier.Evidence evidence) {
        try {
            return json.writeValueAsString(Map.of(
                    "verified", true, "verifierId", evidence.verifierId(),
                    "verifierVersion", evidence.verifierVersion(),
                    "verdict", evidence.verdict().name(),
                    "subjectSha256", evidence.subjectSha256()));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encode verifier result", error);
        }
    }

    private static boolean terminal(ToolExecutionLedgerView value) {
        return value.status() == ToolExecutionStatus.SUCCEEDED
                || value.status() == ToolExecutionStatus.FAILED
                || value.status() == ToolExecutionStatus.TIMED_OUT
                || value.status() == ToolExecutionStatus.CANCELLED;
    }

    private static BusinessException revisionConflict() {
        return business("Tool execution revision changed",
                "TOOL_EFFECT_VERIFIER_REVISION_CONFLICT", HttpStatus.CONFLICT);
    }

    private static BusinessException business(String message, String code, HttpStatus status) {
        return new BusinessException(message, status, code);
    }
}
