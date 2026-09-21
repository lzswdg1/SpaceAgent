package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpInstallationState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpRemoteToolGateway;
import com.spaceagent.platform.tooling.domain.McpServerVersionState;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class McpRemoteToolApplicationService implements McpRemoteToolApplicationApi {
    private final McpMarketplaceRepository repository;
    private final IdentityApplicationApi identity;
    private final McpRemoteToolGateway gateway;
    private final McpConnectionAuthorizationService authorization;

    public McpRemoteToolApplicationService(
            McpMarketplaceRepository repository,
            IdentityApplicationApi identity,
            McpRemoteToolGateway gateway,
            McpConnectionAuthorizationService authorization) {
        this.repository = repository;
        this.identity = identity;
        this.gateway = gateway;
        this.authorization = authorization;
    }

    @Override
    public List<ToolView> listTools(String tenantId, String userId, String connectionId) {
        var authorized = authorization.authorize(require(tenantId, userId, connectionId));
        return gateway.listTools(authorized.connection(), authorized.authorization()).stream()
                .map(value -> new ToolView(
                        value.name(), value.description(), value.inputSchema(),
                        value.readOnly(), value.destructive()))
                .toList();
    }

    @Override
    public ToolResult callTool(CallCommand command) {
        var authorized = authorization.authorize(require(
                command.tenantId(), command.userId(), command.connectionId()));
        if (command.toolName() == null || command.toolName().isBlank()) {
            throw new IllegalArgumentException("toolName required");
        }
        var result = gateway.callTool(
                authorized.connection(), authorized.authorization(),
                command.toolName(), command.arguments());
        return new ToolResult(result.error(), result.structuredContent(), result.text());
    }

    private McpConnection require(String tenantId, String userId, String connectionId) {
        identity.findTenantMembership(tenantId, userId)
                .filter(membership -> membership.status() == TenantMembershipStatus.ACTIVE)
                .orElseThrow(McpRemoteToolApplicationService::denied);
        McpConnection connection = repository.findConnection(connectionId)
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.state() == McpConnectionState.ACTIVE)
                .orElseThrow(McpRemoteToolApplicationService::notFound);
        var installation = repository.findInstallation(connection.installationId())
                .filter(value -> value.state() == McpInstallationState.INSTALLED)
                .orElseThrow(McpRemoteToolApplicationService::notFound);
        if (installation.scope() == McpInstallationScope.USER
                && !installation.subjectId().equals(userId)) {
            throw notFound();
        }
        repository.findVersion(installation.serverVersionId())
                .filter(value -> value.entryId().equals(installation.entryId()))
                .filter(value -> value.lifecycleState() != McpServerVersionState.REVOKED)
                .orElseThrow(McpRemoteToolApplicationService::notFound);
        return connection;
    }

    private static BusinessException denied() {
        return new BusinessException(
                "MCP access denied", HttpStatus.FORBIDDEN, "MCP_ACCESS_DENIED");
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "MCP connection not found", HttpStatus.NOT_FOUND, "MCP_NOT_FOUND");
    }
}
