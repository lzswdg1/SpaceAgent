package com.spaceagent.platform.project.domain;

/**
 * Project-side port for an ephemeral remote-source checkout capability.
 * Implementations must never persist or expose the authorization header.
 */
public interface WorkspaceCheckoutCredentialPort {
    CredentialLease acquire(Request request);

    record Request(
            String tenantId,
            String userId,
            String workspaceId,
            String sourceRepositoryId,
            String connectionId,
            String providerRepositoryId,
            String cloneUrl) {
    }

    interface CredentialLease extends AutoCloseable {
        String authorizationHeader();

        @Override
        void close();
    }
}
