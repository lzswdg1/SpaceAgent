package com.spaceagent.platform.agent.infrastructure.persistence;

import com.spaceagent.platform.agent.domain.AgentApiKey;
import com.spaceagent.platform.agent.domain.AgentApiKeyRepository;
import com.spaceagent.platform.agent.domain.AgentApiKeyScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresAgentApiKeyRepository implements AgentApiKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAgentApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AgentApiKey> findByHash(String keyHash) {
        return jdbcTemplate.query(selectSql() + " WHERE key_hash = ?", this::map, keyHash)
                .stream().findFirst();
    }

    @Override
    public Optional<AgentApiKey> findById(String keyId) {
        return jdbcTemplate.query(selectSql() + " WHERE id = ?", this::map, keyId)
                .stream().findFirst();
    }

    @Override
    public List<AgentApiKey> findByAgentId(String agentId) {
        return jdbcTemplate.query(
                selectSql() + " WHERE agent_id = ? ORDER BY created_at DESC",
                this::map,
                agentId);
    }

    @Override
    public void save(AgentApiKey apiKey) {
        jdbcTemplate.update("""
                INSERT INTO platform_agent_api_keys (
                    id, agent_id, name, key_hash, key_prefix, scopes,
                    enabled, created_at, last_used_at, expires_at, revoked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                apiKey.id(), apiKey.agentId(), apiKey.name(), apiKey.keyHash(), apiKey.keyPrefix(),
                scopes(apiKey.scopes()), apiKey.enabled(), Timestamp.from(apiKey.createdAt()),
                timestamp(apiKey.lastUsedAt()), timestamp(apiKey.expiresAt()), timestamp(apiKey.revokedAt()));
    }

    @Override
    public boolean revoke(String agentId, String keyId, Instant revokedAt) {
        return jdbcTemplate.update("""
                UPDATE platform_agent_api_keys
                   SET enabled = FALSE, revoked_at = ?
                 WHERE id = ? AND agent_id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), keyId, agentId) > 0;
    }

    @Override
    public void markUsed(String keyId, Instant usedAt) {
        jdbcTemplate.update(
                "UPDATE platform_agent_api_keys SET last_used_at = ? WHERE id = ?",
                Timestamp.from(usedAt), keyId);
    }

    private String selectSql() {
        return """
                SELECT id, agent_id, name, key_hash, key_prefix, scopes,
                       enabled, created_at, last_used_at, expires_at, revoked_at
                FROM platform_agent_api_keys
                """;
    }

    private AgentApiKey map(ResultSet rs, int rowNum) throws SQLException {
        return new AgentApiKey(
                rs.getString("id"), rs.getString("agent_id"), rs.getString("name"),
                rs.getString("key_hash"), rs.getString("key_prefix"), parseScopes(rs.getString("scopes")),
                rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("last_used_at")), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("revoked_at")));
    }

    private String scopes(Set<AgentApiKeyScope> scopes) {
        return scopes.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private Set<AgentApiKeyScope> parseScopes(String scopes) {
        return Arrays.stream(scopes.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(AgentApiKeyScope::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
