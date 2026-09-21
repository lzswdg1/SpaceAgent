package com.spaceagent.platform.tooling.infrastructure.persistence;

import com.spaceagent.platform.tooling.domain.McpOAuthState;
import com.spaceagent.platform.tooling.domain.McpOAuthStateRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresMcpOAuthStateRepository implements McpOAuthStateRepository {
    private static final String REDACTED = "REDACTED";
    private final JdbcTemplate jdbc;

    public PostgresMcpOAuthStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(McpOAuthState state) {
        jdbc.update("""
                INSERT INTO platform_mcp_oauth_states(
                    id, connection_id, connection_revision, tenant_id, user_id, state_hash,
                    encrypted_provider_session, expires_at, created_at, consumed_at)
                VALUES(CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, NULL)
                """, state.id(), state.connectionId(), state.connectionRevision(),
                state.tenantId(), state.userId(), state.stateHash(),
                state.encryptedProviderSession(), Timestamp.from(state.expiresAt()),
                Timestamp.from(state.createdAt()));
    }

    @Override
    public Optional<McpOAuthState> consume(
            String stateHash, String tenantId, String userId, Instant now) {
        return jdbc.query("""
                UPDATE platform_mcp_oauth_states SET consumed_at = ?
                 WHERE state_hash = ? AND tenant_id = ? AND user_id = ?
                   AND consumed_at IS NULL AND expires_at > ?
                RETURNING *
                """, this::map, Timestamp.from(now), stateHash, tenantId, userId,
                Timestamp.from(now)).stream().findFirst();
    }

    @Override
    public void redact(String id, Instant now) {
        jdbc.update("""
                UPDATE platform_mcp_oauth_states
                   SET encrypted_provider_session = ?, provider_session_redacted_at = ?
                 WHERE id = CAST(? AS UUID) AND consumed_at IS NOT NULL
                """, REDACTED, Timestamp.from(now), id);
    }

    private McpOAuthState map(ResultSet result, int row) throws SQLException {
        Timestamp consumed = result.getTimestamp("consumed_at");
        return new McpOAuthState(
                result.getString("id"), result.getString("connection_id"),
                result.getLong("connection_revision"), result.getString("tenant_id"),
                result.getString("user_id"), result.getString("state_hash"),
                result.getString("encrypted_provider_session"),
                result.getTimestamp("expires_at").toInstant(),
                result.getTimestamp("created_at").toInstant(),
                consumed == null ? null : consumed.toInstant());
    }
}
