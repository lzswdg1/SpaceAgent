package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.project.domain.WorkspaceCheckoutCredentialPort;
import com.spaceagent.platform.tooling.api.GithubMcpCheckoutApplicationApi;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps the Project module independent from Tooling while supplying an in-process lease. */
@Component
public class GithubMcpWorkspaceCheckoutCredentialAdapter
        implements WorkspaceCheckoutCredentialPort {
    private final GithubMcpCheckoutApplicationApi tooling;

    public GithubMcpWorkspaceCheckoutCredentialAdapter(
            GithubMcpCheckoutApplicationApi tooling) {
        this.tooling = tooling;
    }

    @Override
    public CredentialLease acquire(Request request) {
        GithubMcpCheckoutApplicationApi.CheckoutGrantView grant = tooling.prepare(
                new GithubMcpCheckoutApplicationApi.PrepareCommand(
                        request.tenantId(), request.userId(), request.workspaceId(),
                        request.sourceRepositoryId(), request.connectionId(),
                        request.providerRepositoryId(), request.cloneUrl()));
        return new Lease(tooling, request.tenantId(), request.userId(), grant);
    }

    private static final class Lease implements CredentialLease {
        private final GithubMcpCheckoutApplicationApi tooling;
        private final String tenantId;
        private final String userId;
        private final GithubMcpCheckoutApplicationApi.CheckoutGrantView grant;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(
                GithubMcpCheckoutApplicationApi tooling,
                String tenantId,
                String userId,
                GithubMcpCheckoutApplicationApi.CheckoutGrantView grant) {
            this.tooling = tooling;
            this.tenantId = tenantId;
            this.userId = userId;
            this.grant = grant;
        }

        @Override
        public String authorizationHeader() {
            if (closed.get()) throw new IllegalStateException("Checkout credential is closed");
            return grant.authorizationHeader();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                tooling.consume(new GithubMcpCheckoutApplicationApi.ConsumeCommand(
                        tenantId, userId, grant.grantId()));
            } catch (RuntimeException ignored) {
                // The grant is still bounded by its remote and encrypted-storage expiry.
            }
        }

        @Override
        public String toString() {
            return "GithubMcpWorkspaceCheckoutCredentialLease[grantId="
                    + grant.grantId() + ", authorizationHeader=<redacted>]";
        }
    }
}
