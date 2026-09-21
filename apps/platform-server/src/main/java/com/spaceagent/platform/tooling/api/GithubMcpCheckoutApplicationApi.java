package com.spaceagent.platform.tooling.api;

import java.time.Instant;

/** Internal secret-bearing API. It has no HTTP adapter. */
public interface GithubMcpCheckoutApplicationApi {
    CheckoutGrantView prepare(PrepareCommand command);

    void consume(ConsumeCommand command);

    record PrepareCommand(
            String tenantId,
            String userId,
            String workspaceId,
            String sourceRepositoryId,
            String connectionId,
            String providerRepositoryId,
            String cloneUrl) {
    }

    record ConsumeCommand(String tenantId, String userId, String grantId) {
    }

    final class CheckoutGrantView {
        private final String grantId;
        private final String authorizationHeader;
        private final Instant expiresAt;

        public CheckoutGrantView(
                String grantId, String authorizationHeader, Instant expiresAt) {
            if (grantId == null || grantId.isBlank()
                    || authorizationHeader == null || authorizationHeader.isBlank()
                    || expiresAt == null) {
                throw new IllegalArgumentException("Invalid MCP checkout grant");
            }
            this.grantId = grantId;
            this.authorizationHeader = authorizationHeader;
            this.expiresAt = expiresAt;
        }

        public String grantId() { return grantId; }
        public String authorizationHeader() { return authorizationHeader; }
        public Instant expiresAt() { return expiresAt; }

        @Override
        public String toString() {
            return "CheckoutGrantView[grantId=" + grantId
                    + ", authorizationHeader=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }
}
