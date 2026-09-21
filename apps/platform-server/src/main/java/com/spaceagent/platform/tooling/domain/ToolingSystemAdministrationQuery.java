package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.List;

public interface ToolingSystemAdministrationQuery {
    OverviewRow overview();
    PageRows<McpRow> mcpCredentials(int offset, int limit);
    PageRows<ResourceRow> mcpConnectionsByManager(String userId, int offset, int limit);
    PageRows<ResourceRow> toolEffectsByOwner(String userId, int offset, int limit);
    DeletionEvidenceRow deletionEvidence(String userId, Instant now);

    record OverviewRow(long mcpConnections, long activeMcpConnections,
                       long unknownToolExecutions) {
    }

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record McpRow(String id, String organizationId, String managedByUserId,
                  String profile, String transport, String endpointUrl, String authType,
                  boolean authConfigured, String state, String externalAccountName,
                  Instant createdAt, Instant updatedAt, Instant revokedAt) {
    }
    record ResourceRow(String id, String organizationId, String parentId, String displayName,
                       String state, String relation, Instant createdAt, Instant updatedAt,
                       String safeErrorCode, long primaryCount, long secondaryCount) {
    }
    record DeletionEvidenceRow(long managedConnections, long activeCheckoutGrants,
                               long unknownToolExecutions) {
    }
}
