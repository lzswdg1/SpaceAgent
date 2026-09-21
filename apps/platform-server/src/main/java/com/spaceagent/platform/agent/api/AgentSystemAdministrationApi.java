package com.spaceagent.platform.agent.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;

import java.time.Instant;

public interface AgentSystemAdministrationApi {
    AgentOverview overview();

    SystemAdministrationPage<AgentKeyCredential> agentKeyCredentials(int page, int pageSize);

    SystemAdministrationPage<AgentSummary> agentsByOwner(String userId, int page, int pageSize);

    AgentDeletionEvidence deletionEvidence(String userId);

    record AgentOverview(long agents, long activeAgents, long activeApiKeys) {
    }

    record AgentKeyCredential(
            String kind,
            String id,
            String agentId,
            String agentName,
            String organizationId,
            String ownerUserId,
            String name,
            String keyPrefix,
            String scopes,
            boolean enabled,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            Instant revokedAt) {
    }

    record AgentSummary(String id, String organizationId, String ownerUserId, String name,
                        String description, String status,
                        long revision, long activeKeyCount, Instant createdAt, Instant updatedAt,
                        Instant archivedAt) {
    }

    record AgentDeletionEvidence(long ownedAgents, long activeApiKeys) {
    }
}
