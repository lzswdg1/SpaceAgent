package com.spaceagent.platform.tooling.domain;

public interface McpInvocationLedgerRepository {
    ClaimDecision claim(ClaimRequest request);

    Transition complete(CompleteRequest request);

    Transition markUnknown(UnknownRequest request);

    record ClaimRequest(
            String id,
            String tenantId,
            String userId,
            String connectionId,
            String operationKey,
            String idempotencyKeyHash,
            String toolName,
            String argumentsJson,
            String inputHash,
            String claimToken,
            String claimOwner,
            long leaseSeconds) {
        public ClaimRequest {
            require(id, "id");
            require(tenantId, "tenantId");
            require(userId, "userId");
            require(connectionId, "connectionId");
            require(operationKey, "operationKey");
            require(toolName, "toolName");
            require(argumentsJson, "argumentsJson");
            require(claimToken, "claimToken");
            require(claimOwner, "claimOwner");
            if (idempotencyKeyHash == null || idempotencyKeyHash.length() != 64
                    || inputHash == null || inputHash.length() != 64 || leaseSeconds <= 0) {
                throw new IllegalArgumentException("Invalid MCP invocation claim");
            }
        }
    }

    record ClaimDecision(McpInvocationClaimType type, McpInvocationLedger ledger) {
    }

    record CompleteRequest(
            String id,
            String claimToken,
            long expectedRevision,
            McpInvocationStatus status,
            String resultJson,
            String errorCode) {
        public CompleteRequest {
            require(id, "id");
            require(claimToken, "claimToken");
            if (expectedRevision <= 0 || (status != McpInvocationStatus.SUCCEEDED
                    && status != McpInvocationStatus.FAILED)) {
                throw new IllegalArgumentException("Invalid MCP invocation completion");
            }
        }
    }

    record UnknownRequest(
            String id,
            String claimToken,
            long expectedRevision,
            String errorCode) {
        public UnknownRequest {
            require(id, "id");
            require(claimToken, "claimToken");
            require(errorCode, "errorCode");
            if (expectedRevision <= 0) {
                throw new IllegalArgumentException("Invalid MCP invocation unknown transition");
            }
        }
    }

    record Transition(McpInvocationTransitionType type, McpInvocationLedger ledger) {
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
