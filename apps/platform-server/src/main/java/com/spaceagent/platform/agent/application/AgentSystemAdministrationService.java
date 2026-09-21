package com.spaceagent.platform.agent.application;

import com.spaceagent.platform.agent.api.AgentSystemAdministrationApi;
import com.spaceagent.platform.agent.domain.AgentSystemAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class AgentSystemAdministrationService implements AgentSystemAdministrationApi {
    private final AgentSystemAdministrationQuery query;
    private final TimeProvider timeProvider;

    public AgentSystemAdministrationService(AgentSystemAdministrationQuery query, TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }

    @Override
    public AgentOverview overview() {
        var row = query.overview(timeProvider.now());
        return new AgentOverview(row.agents(), row.activeAgents(), row.activeApiKeys());
    }

    @Override
    public SystemAdministrationPage<AgentKeyCredential> agentKeyCredentials(int page, int pageSize) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.agentKeyCredentials(safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row ->
                new AgentKeyCredential("AGENT_KEY", row.id(), row.agentId(), row.agentName(),
                        row.organizationId(), row.ownerUserId(), row.name(), row.keyPrefix(),
                        row.scopes(), row.enabled(), row.createdAt(), row.lastUsedAt(),
                        row.expiresAt(), row.revokedAt())).toList(), safePage, safeSize,
                rows.total(), timeProvider.now());
    }

    @Override
    public SystemAdministrationPage<AgentSummary> agentsByOwner(
            String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.agentsByOwner(userId.trim(), safePage * safeSize, safeSize,
                timeProvider.now());
        return new SystemAdministrationPage<>(rows.items().stream().map(row ->
                new AgentSummary(row.id(), row.organizationId(), row.ownerUserId(), row.name(),
                        row.description(), row.status(), row.revision(),
                        row.activeKeyCount(), row.createdAt(), row.updatedAt(), row.archivedAt()))
                .toList(), safePage, safeSize, rows.total(), timeProvider.now());
    }

    @Override public AgentDeletionEvidence deletionEvidence(String userId) {
        var row = query.deletionEvidence(userId, timeProvider.now());
        return new AgentDeletionEvidence(row.ownedAgents(), row.activeApiKeys());
    }
}
