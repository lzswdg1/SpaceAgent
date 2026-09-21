package com.spaceagent.platform.agent.infrastructure.persistence;

import com.spaceagent.platform.agent.domain.AgentSystemAdministrationQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAgentSystemAdministrationQuery implements AgentSystemAdministrationQuery {
    private final JdbcTemplate jdbc;

    public PostgresAgentSystemAdministrationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OverviewRow overview(Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) agents,
                       count(*) FILTER (WHERE status = 'ACTIVE') active_agents,
                       (SELECT count(*) FROM platform_agent_api_keys key_row
                         WHERE key_row.enabled AND key_row.revoked_at IS NULL
                           AND (key_row.expires_at IS NULL OR key_row.expires_at > ?)) active_api_keys
                FROM platform_agent_definitions
                """, (rs, row) -> new OverviewRow(rs.getLong("agents"),
                rs.getLong("active_agents"), rs.getLong("active_api_keys")), Timestamp.from(now));
    }

    @Override
    public PageRows<KeyRow> agentKeyCredentials(int offset, int limit) {
        var items = jdbc.query("""
                SELECT key_row.id, key_row.agent_id, agent.name agent_name, agent.tenant_id,
                       agent.owner_id, key_row.name, key_row.key_prefix, key_row.scopes,
                       key_row.enabled, key_row.created_at, key_row.last_used_at,
                       key_row.expires_at, key_row.revoked_at
                FROM platform_agent_api_keys key_row
                JOIN platform_agent_definitions agent ON agent.id = key_row.agent_id
                ORDER BY key_row.created_at DESC, key_row.id
                LIMIT ? OFFSET ?
                """, this::map, limit, offset);
        Long total = jdbc.queryForObject("SELECT count(*) FROM platform_agent_api_keys", Long.class);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public PageRows<AgentRow> agentsByOwner(String userId, int offset, int limit, Instant now) {
        var items = jdbc.query("""
                SELECT agent.id, agent.tenant_id, agent.owner_id, agent.name, agent.description,
                       agent.status, agent.revision,
                       agent.created_at, agent.updated_at, agent.archived_at,
                       (SELECT count(*) FROM platform_agent_api_keys key_row
                         WHERE key_row.agent_id = agent.id AND key_row.enabled
                           AND key_row.revoked_at IS NULL
                           AND (key_row.expires_at IS NULL OR key_row.expires_at > ?)) active_api_keys
                FROM platform_agent_definitions agent
                WHERE agent.owner_id = ?
                ORDER BY agent.created_at DESC, agent.id
                LIMIT ? OFFSET ?
                """, this::mapAgent, Timestamp.from(now), userId, limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform_agent_definitions WHERE owner_id = ?",
                Long.class, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public DeletionEvidenceRow deletionEvidence(String userId, Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) owned_agents,
                       (SELECT count(*) FROM platform_agent_api_keys key_row
                         JOIN platform_agent_definitions agent ON agent.id = key_row.agent_id
                        WHERE agent.owner_id = ? AND key_row.enabled AND key_row.revoked_at IS NULL
                          AND (key_row.expires_at IS NULL OR key_row.expires_at > ?)) active_api_keys
                FROM platform_agent_definitions WHERE owner_id = ?
                """, (rs, row) -> new DeletionEvidenceRow(rs.getLong("owned_agents"),
                rs.getLong("active_api_keys")), userId, Timestamp.from(now), userId);
    }

    private KeyRow map(ResultSet rs, int row) throws SQLException {
        return new KeyRow(rs.getString("id"), rs.getString("agent_id"),
                rs.getString("agent_name"), rs.getString("tenant_id"), rs.getString("owner_id"),
                rs.getString("name"), rs.getString("key_prefix"), rs.getString("scopes"),
                rs.getBoolean("enabled"), instant(rs, "created_at"),
                nullableInstant(rs, "last_used_at"), nullableInstant(rs, "expires_at"),
                nullableInstant(rs, "revoked_at"));
    }

    private AgentRow mapAgent(ResultSet rs, int row) throws SQLException {
        return new AgentRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("owner_id"), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), rs.getLong("revision"), rs.getLong("active_api_keys"),
                instant(rs, "created_at"), instant(rs, "updated_at"),
                nullableInstant(rs, "archived_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
