package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.LocalWorkspaceBridge;
import com.spaceagent.platform.project.domain.LocalWorkspaceBridgeRepository;
import com.spaceagent.platform.project.domain.LocalWorkspaceBridgeState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresLocalWorkspaceBridgeRepository implements LocalWorkspaceBridgeRepository {
    private static final String COLUMNS = """
            id, tenant_id, owner_id, display_name, device_id, root_handle,
            token_hash, token_prefix, state, last_seen_at, created_at, updated_at, revoked_at
            """;
    private final JdbcTemplate jdbc;
    public PostgresLocalWorkspaceBridgeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void save(LocalWorkspaceBridge value) {
        jdbc.update("""
                INSERT INTO platform_local_workspace_bridges (
                    id, tenant_id, owner_id, display_name, device_id, root_handle,
                    token_hash, token_prefix, state, last_seen_at, created_at, updated_at, revoked_at
                ) VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    display_name=EXCLUDED.display_name, state=EXCLUDED.state,
                    last_seen_at=EXCLUDED.last_seen_at, updated_at=EXCLUDED.updated_at,
                    revoked_at=EXCLUDED.revoked_at
                """, value.id(), value.tenantId(), value.ownerId(), value.displayName(),
                value.deviceId(), value.rootHandle(), value.tokenHash(), value.tokenPrefix(),
                value.state().name(), Timestamp.from(value.lastSeenAt()),
                Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()),
                timestamp(value.revokedAt()));
    }
    public Optional<LocalWorkspaceBridge> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_local_workspace_bridges WHERE id=CAST(? AS UUID)", this::map, id).stream().findFirst();
    }
    public Optional<LocalWorkspaceBridge> findByTokenHash(String hash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_local_workspace_bridges WHERE token_hash=?", this::map, hash).stream().findFirst();
    }
    public List<LocalWorkspaceBridge> findByOwner(String tenantId, String ownerId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_local_workspace_bridges WHERE tenant_id=? AND owner_id=? ORDER BY created_at,id", this::map, tenantId, ownerId);
    }
    public boolean exists(String tenantId, String ownerId, String deviceId, String rootHandle) {
        Boolean value = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform_local_workspace_bridges
                 WHERE tenant_id=? AND owner_id=? AND device_id=? AND root_handle=? AND state='ACTIVE')
                """, Boolean.class, tenantId, ownerId, deviceId, rootHandle);
        return Boolean.TRUE.equals(value);
    }
    private LocalWorkspaceBridge map(ResultSet rs, int row) throws SQLException {
        return new LocalWorkspaceBridge(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("owner_id"),
                rs.getString("display_name"), rs.getString("device_id"), rs.getString("root_handle"),
                rs.getString("token_hash"), rs.getString("token_prefix"),
                LocalWorkspaceBridgeState.valueOf(rs.getString("state")),
                rs.getTimestamp("last_seen_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), instant(rs.getTimestamp("revoked_at")));
    }
    private static Timestamp timestamp(java.time.Instant value) { return value == null ? null : Timestamp.from(value); }
    private static java.time.Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
