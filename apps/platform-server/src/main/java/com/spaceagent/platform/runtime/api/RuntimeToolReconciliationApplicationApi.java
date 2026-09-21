package com.spaceagent.platform.runtime.api;

import java.time.Instant;
import java.util.Map;

/** Safe Tool-specific UNKNOWN reconciliation; callers cannot select a terminal state. */
public interface RuntimeToolReconciliationApplicationApi {
    ReconciliationView reconcile(ReconcileCommand command);

    record ReconcileCommand(
            String tenantId,
            String userId,
            String agentRunId,
            String toolCallId,
            long expectedRevision,
            String reason) {
        public ReconcileCommand {
            require(tenantId, "tenantId");
            require(userId, "userId");
            require(agentRunId, "agentRunId");
            require(toolCallId, "toolCallId");
            require(reason, "reason");
            if (expectedRevision <= 0) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
        }

        private static void require(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    record ReconciliationView(
            String agentRunId,
            String toolCallId,
            String toolName,
            String transition,
            String status,
            long revision,
            String verifier,
            Map<String, String> evidence,
            Instant resolvedAt) {
        public ReconciliationView {
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        }
    }
}
