package com.spaceagent.platform.agent.domain;

import java.time.Instant;
import java.util.List;

public interface AgentSystemAdministrationQuery {
    OverviewRow overview(Instant now);

    PageRows<KeyRow> agentKeyCredentials(int offset, int limit);

    PageRows<AgentRow> agentsByOwner(String userId, int offset, int limit, Instant now);

    DeletionEvidenceRow deletionEvidence(String userId, Instant now);

    record OverviewRow(long agents, long activeAgents, long activeApiKeys) {
    }

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record KeyRow(String id, String agentId, String agentName, String organizationId,
                  String ownerUserId, String name, String keyPrefix, String scopes,
                  boolean enabled, Instant createdAt, Instant lastUsedAt,
                  Instant expiresAt, Instant revokedAt) {
    }

    record AgentRow(String id, String organizationId, String ownerUserId, String name,
                    String description, String status,
                    long revision, long activeKeyCount, Instant createdAt, Instant updatedAt,
                    Instant archivedAt) {
    }

    record DeletionEvidenceRow(long ownedAgents, long activeApiKeys) {
    }
}
