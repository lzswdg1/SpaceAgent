package com.spaceagent.platform.tooling.infrastructure.persistence;

import com.spaceagent.platform.tooling.domain.ToolingSystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresToolingSystemAdministrationQuery implements ToolingSystemAdministrationQuery {
    private final JdbcTemplate jdbc;
    public PostgresToolingSystemAdministrationQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public OverviewRow overview() {
        return jdbc.queryForObject("""
                SELECT count(*) mcp_connections,
                       count(*) FILTER (WHERE state = 'ACTIVE') active_mcp_connections,
                       (SELECT count(*) FROM platform_tool_execution_ledger
                         WHERE status = 'UNKNOWN') unknown_tool_executions
                FROM platform_mcp_connections
                """, (rs, row) -> new OverviewRow(rs.getLong("mcp_connections"),
                rs.getLong("active_mcp_connections"), rs.getLong("unknown_tool_executions")));
    }

    @Override public PageRows<McpRow> mcpCredentials(int offset, int limit) {
        var items = jdbc.query("""
                SELECT connection.id, connection.tenant_id, connection.managed_by,
                       entry.slug profile, entry.transport, connection.endpoint_url,
                       connection.auth_type,
                       connection.auth_type <> 'NONE'
                         AND connection.encrypted_auth_json IS NOT NULL
                         AND connection.encrypted_auth_json <> '' AS auth_configured,
                       connection.state, connection.external_account_name,
                       connection.created_at, connection.updated_at, connection.revoked_at
                FROM platform_mcp_connections connection
                JOIN platform_mcp_installations installation
                  ON installation.id = connection.installation_id
                JOIN platform_mcp_marketplace_entries entry ON entry.id = installation.entry_id
                ORDER BY connection.created_at DESC, connection.id
                LIMIT ? OFFSET ?
                """, this::map, limit, offset);
        Long total = jdbc.queryForObject("SELECT count(*) FROM platform_mcp_connections", Long.class);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override public PageRows<ResourceRow> mcpConnectionsByManager(
            String userId, int offset, int limit) {
        var items = jdbc.query("""
                SELECT connection.id::text id, connection.tenant_id,
                       connection.installation_id::text parent_id, entry.name,
                       connection.state status, entry.slug || ':' || entry.transport relation,
                       connection.created_at, connection.updated_at,
                       CASE WHEN connection.state = 'ERROR' THEN 'MCP_CONNECTION_ERROR' END safe_error_code,
                       0::bigint primary_count, 0::bigint secondary_count
                FROM platform_mcp_connections connection
                JOIN platform_mcp_installations installation
                  ON installation.id = connection.installation_id
                JOIN platform_mcp_marketplace_entries entry ON entry.id = installation.entry_id
                WHERE connection.managed_by = ?
                ORDER BY connection.updated_at DESC, connection.id LIMIT ? OFFSET ?
                """, this::mapResource, userId, limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform_mcp_connections WHERE managed_by = ?",
                Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override public PageRows<ResourceRow> toolEffectsByOwner(
            String userId, int offset, int limit) {
        var items = jdbc.query("""
                SELECT tool.id, run.tenant_id, tool.agent_run_id parent_id, tool.tool_name name,
                       tool.status, 'TOOL_CALL'::text relation, tool.started_at created_at,
                       COALESCE(tool.completed_at, tool.started_at) updated_at,
                       CASE WHEN tool.error ~ '^[A-Z0-9_]{1,160}$' THEN tool.error END safe_error_code,
                       0::bigint primary_count, 0::bigint secondary_count
                FROM platform_tool_execution_ledger tool
                JOIN platform_agent_runs run ON run.id = tool.agent_run_id
                WHERE run.owner_id = ? AND tool.status IN ('FAILED','TIMED_OUT','UNKNOWN')
                ORDER BY COALESCE(tool.completed_at, tool.started_at) DESC, tool.id
                LIMIT ? OFFSET ?
                """, this::mapResource, userId, limit, offset);
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM platform_tool_execution_ledger tool
                JOIN platform_agent_runs run ON run.id = tool.agent_run_id
                WHERE run.owner_id = ? AND tool.status IN ('FAILED','TIMED_OUT','UNKNOWN')
                """, Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override public DeletionEvidenceRow deletionEvidence(String userId, Instant now) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM platform_mcp_connections WHERE managed_by = ?) managed_connections,
                  (SELECT count(*) FROM platform_mcp_invocation_ledger
                    WHERE user_id = ? AND checkout_expires_at > ?
                      AND checkout_consumed_at IS NULL) active_checkout_grants,
                  (SELECT count(*) FROM platform_tool_execution_ledger tool
                    JOIN platform_agent_runs run ON run.id = tool.agent_run_id
                   WHERE run.owner_id = ? AND tool.status = 'UNKNOWN') unknown_tool_executions
                """, (rs, row) -> new DeletionEvidenceRow(rs.getLong("managed_connections"),
                rs.getLong("active_checkout_grants"), rs.getLong("unknown_tool_executions")),
                userId, userId, java.sql.Timestamp.from(now), userId);
    }

    private McpRow map(ResultSet rs, int row) throws SQLException {
        return new McpRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("managed_by"), rs.getString("profile"), rs.getString("transport"),
                rs.getString("endpoint_url"), rs.getString("auth_type"),
                rs.getBoolean("auth_configured"), rs.getString("state"),
                rs.getString("external_account_name"), instant(rs, "created_at"),
                instant(rs, "updated_at"), nullableInstant(rs, "revoked_at"));
    }

    private ResourceRow mapResource(ResultSet rs, int row) throws SQLException {
        return new ResourceRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("parent_id"), rs.getString("name"), rs.getString("status"),
                rs.getString("relation"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getString("safe_error_code"), rs.getLong("primary_count"),
                rs.getLong("secondary_count"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
