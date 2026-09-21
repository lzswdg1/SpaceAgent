package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;

import java.time.Instant;

public interface ToolingSystemAdministrationApi {
    ToolingOverview overview();

    SystemAdministrationPage<McpCredential> mcpCredentials(int page, int pageSize);

    SystemAdministrationPage<UserResourceSummary> mcpConnectionsByManager(
            String userId, int page, int pageSize);

    SystemAdministrationPage<UserResourceSummary> toolEffectsByOwner(
            String userId, int page, int pageSize);

    ToolingDeletionEvidence deletionEvidence(String userId);

    record ToolingOverview(long mcpConnections, long activeMcpConnections,
                           long unknownToolExecutions) {
    }

    record McpCredential(
            String kind,
            String id,
            String organizationId,
            String managedByUserId,
            String profile,
            String transport,
            String endpointUrl,
            String endpointHost,
            String authType,
            boolean authConfigured,
            String state,
            String externalAccountName,
            Instant createdAt,
            Instant updatedAt,
            Instant revokedAt) {
    }

    record ToolingDeletionEvidence(long managedConnections, long activeCheckoutGrants,
                                   long unknownToolExecutions) {
    }
}
