package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Secret-bearing extension of the MCP Invocation Ledger used only for short-lived checkout.
 * Plaintext authorization never crosses this persistence port.
 */
public interface McpCheckoutGrantRepository {
    McpInvocationTransitionType completeWithGrant(CompleteGrantRequest request);

    Optional<StoredGrant> findAvailable(GrantQuery query);

    void consume(GrantQuery query);

    int redactExpired();

    record CompleteGrantRequest(
            String invocationId,
            String tenantId,
            String userId,
            String claimToken,
            long expectedRevision,
            String encryptedGrantJson,
            String evidenceHash,
            Instant expiresAt) {
        public CompleteGrantRequest {
            require(invocationId, "invocationId");
            require(tenantId, "tenantId");
            require(userId, "userId");
            require(claimToken, "claimToken");
            require(encryptedGrantJson, "encryptedGrantJson");
            if (expectedRevision <= 0 || evidenceHash == null || evidenceHash.length() != 64
                    || expiresAt == null) {
                throw new IllegalArgumentException("Invalid MCP checkout grant completion");
            }
        }
    }

    record GrantQuery(String invocationId, String tenantId, String userId) {
        public GrantQuery {
            require(invocationId, "invocationId");
            require(tenantId, "tenantId");
            require(userId, "userId");
        }
    }

    record StoredGrant(String invocationId, String encryptedGrantJson, Instant expiresAt) {
        public StoredGrant {
            require(invocationId, "invocationId");
            require(encryptedGrantJson, "encryptedGrantJson");
            if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
