package com.spaceagent.platform.tooling.infrastructure.persistence;

import com.spaceagent.platform.tooling.api.ToolingCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresToolingCleanupService implements ToolingCleanupApplicationApi {
    private final JdbcTemplate jdbc;

    public PostgresToolingCleanupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override @Transactional
    public void cleanupOrganization(String organizationId) {
        jdbc.update("DELETE FROM platform_mcp_invocation_ledger WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_mcp_oauth_states WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_mcp_connections WHERE tenant_id = ?", organizationId);
        jdbc.update("DELETE FROM platform_mcp_installations WHERE tenant_id = ?", organizationId);
    }

    @Override @Transactional
    public void cleanupUser(String userId) {
        Long managed = jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_connections WHERE managed_by = ?", Long.class, userId);
        if (managed != null && managed > 0L) throw new IllegalStateException("USER_MANAGES_MCP_CONNECTIONS");
        jdbc.update("DELETE FROM platform_mcp_invocation_ledger WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM platform_mcp_oauth_states WHERE user_id = ?", userId);
        jdbc.update("""
                DELETE FROM platform_mcp_installations
                 WHERE scope = 'USER' AND subject_id = ?
                """, userId);
    }
}
