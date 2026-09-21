package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.IdentityPresenceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentityPresenceRepository implements IdentityPresenceRepository {
    private final JdbcTemplate jdbc;
    public PostgresIdentityPresenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String AUTHORIZED = """
            EXISTS (SELECT 1 FROM platform_users u
              JOIN platform_tenant_memberships m ON m.user_id=u.id AND m.tenant_id=p.tenant_id
              JOIN platform_tenants t ON t.id=m.tenant_id
              WHERE u.id=p.user_id AND u.status='ACTIVE' AND u.access_version=p.access_version
                AND m.status='ACTIVE' AND t.status='ACTIVE')
            AND EXISTS (SELECT 1 FROM platform_refresh_tokens r WHERE r.session_id=p.session_id
              AND r.user_id=p.user_id AND r.tenant_id=p.tenant_id AND r.access_version=p.access_version
              AND r.revoked_at IS NULL AND r.expires_at>?)
            AND NOT EXISTS (SELECT 1 FROM platform_access_token_revocations a WHERE a.token_hash=p.access_hash)
            """;

    @Override public boolean heartbeat(UUID sessionId, String userId, String tenantId, long version,
            String hash, String client, Instant now, Instant until) {
        // No caller-provided identity enters this adapter except the verified JWT context.
        return jdbc.update("""
                INSERT INTO platform_presence_leases AS existing
                  (session_id,user_id,tenant_id,access_version,access_hash,client_type,first_seen_at,last_heartbeat_at,expires_at)
                SELECT p.session_id,p.user_id,p.tenant_id,p.access_version,p.access_hash,?,?,?,?
                FROM (SELECT ?::uuid session_id,?::varchar user_id,?::varchar tenant_id,
                             ?::bigint access_version,?::varchar access_hash) p WHERE
                """ + AUTHORIZED + """
                ON CONFLICT(session_id) DO UPDATE SET access_hash=excluded.access_hash,
                  client_type=excluded.client_type,last_heartbeat_at=excluded.last_heartbeat_at,
                  expires_at=excluded.expires_at
                WHERE existing.user_id=excluded.user_id AND existing.tenant_id=excluded.tenant_id
                  AND existing.access_version=excluded.access_version
                  AND existing.last_heartbeat_at<=excluded.last_heartbeat_at
                """, client, Timestamp.from(now), Timestamp.from(now), Timestamp.from(until), sessionId,
                userId, tenantId, version, hash, Timestamp.from(now)) == 1;
    }
    @Override public void leave(UUID sessionId, String userId, Instant now) {
        jdbc.update("UPDATE platform_presence_leases SET expires_at=? WHERE session_id=? AND user_id=?",
                Timestamp.from(now), sessionId, userId);
    }
    @Override public int sweepExpired(Instant expiredBefore, int limit) {
        return jdbc.update("DELETE FROM platform_presence_leases WHERE session_id IN ("
                        + "SELECT session_id FROM platform_presence_leases WHERE expires_at<=?"
                        + " ORDER BY expires_at,session_id LIMIT ?)",
                Timestamp.from(expiredBefore), Math.max(1, Math.min(limit, 5_000)));
    }
    @Override public Counts counts(Instant now) {
        return jdbc.queryForObject("SELECT count(DISTINCT p.user_id) users,count(*) sessions,"
                + "(SELECT count(*) FROM platform_presence_leases) observed FROM platform_presence_leases p "
                + "WHERE p.expires_at>? AND " + AUTHORIZED,
                (rs, row) -> new Counts(rs.getLong("users"), rs.getLong("sessions"), rs.getLong("observed")),
                Timestamp.from(now), Timestamp.from(now));
    }
}
