package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import java.time.Instant;

/** Internal proof boundary; callers cannot select a verifier, terminal state or evidence. */
public interface ToolEffectVerificationApplicationApi {
    VerificationView verify(VerifyCommand command);

    record VerifyCommand(
            String tenantId,
            String ownerUserId,
            String agentRunId,
            String toolCallId,
            long expectedLedgerRevision,
            String reason,
            ToolEffectVerifier.Scope scope) {
        public VerifyCommand {
            require(tenantId, "tenantId");
            require(ownerUserId, "ownerUserId");
            require(agentRunId, "agentRunId");
            require(toolCallId, "toolCallId");
            reason = reason == null ? "" : reason.trim();
            if (expectedLedgerRevision <= 0) {
                throw new IllegalArgumentException("expectedLedgerRevision must be positive");
            }
            if (reason.isEmpty() || reason.length() > 1_000) {
                throw new IllegalArgumentException("reason is invalid");
            }
            if (scope == null) throw new IllegalArgumentException("scope is required");
        }

        private static void require(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    record VerificationView(
            String verifierId,
            int verifierVersion,
            String effectKind,
            String verdict,
            String evidenceSchemaVersion,
            String subjectSha256,
            String observationSha256,
            int observedBytes,
            String safeCode,
            Instant observedAt,
            String transition,
            String ledgerStatus,
            long ledgerRevision,
            Instant resolvedAt) {
    }
}
