package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.platform.shared.api.UserResourceSummary;
import com.spaceagent.platform.tooling.api.ToolingSystemAdministrationApi;
import com.spaceagent.platform.tooling.domain.ToolingSystemAdministrationQuery;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class ToolingSystemAdministrationService implements ToolingSystemAdministrationApi {
    private final ToolingSystemAdministrationQuery query;
    private final TimeProvider timeProvider;

    public ToolingSystemAdministrationService(
            ToolingSystemAdministrationQuery query,
            TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }

    @Override public ToolingOverview overview() {
        var row = query.overview();
        return new ToolingOverview(row.mcpConnections(), row.activeMcpConnections(),
                row.unknownToolExecutions());
    }

    @Override public ToolingDeletionEvidence deletionEvidence(String userId) {
        var row = query.deletionEvidence(userId, timeProvider.now());
        return new ToolingDeletionEvidence(row.managedConnections(), row.activeCheckoutGrants(),
                row.unknownToolExecutions());
    }

    @Override public SystemAdministrationPage<UserResourceSummary> mcpConnectionsByManager(
            String userId, int page, int pageSize) {
        return resourcePage("MCP_CONNECTION", userId, page, pageSize, true);
    }

    @Override public SystemAdministrationPage<UserResourceSummary> toolEffectsByOwner(
            String userId, int page, int pageSize) {
        return resourcePage("TOOL_EFFECT", userId, page, pageSize, false);
    }

    private SystemAdministrationPage<UserResourceSummary> resourcePage(
            String kind, String userId, int page, int pageSize, boolean connections) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = connections
                ? query.mcpConnectionsByManager(userId.trim(), safePage * safeSize, safeSize)
                : query.toolEffectsByOwner(userId.trim(), safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new UserResourceSummary(
                kind, row.id(), row.organizationId(), row.parentId(), row.displayName(), row.state(),
                row.relation(), row.createdAt(), row.updatedAt(), row.safeErrorCode(),
                row.primaryCount(), row.secondaryCount())).toList(), safePage, safeSize, rows.total(),
                timeProvider.now());
    }

    @Override
    public SystemAdministrationPage<McpCredential> mcpCredentials(int page, int pageSize) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.mcpCredentials(safePage * safeSize, safeSize);
        return new SystemAdministrationPage<>(rows.items().stream().map(row -> new McpCredential(
                "MCP", row.id(), row.organizationId(), row.managedByUserId(), row.profile(),
                row.transport(), row.endpointUrl(), endpointHost(row.endpointUrl()), row.authType(),
                row.authConfigured(), row.state(), row.externalAccountName(), row.createdAt(),
                row.updatedAt(), row.revokedAt())).toList(), safePage, safeSize, rows.total(),
                timeProvider.now());
    }

    private static String endpointHost(String endpointUrl) {
        try { return URI.create(endpointUrl).getHost(); }
        catch (RuntimeException error) { return null; }
    }
}
