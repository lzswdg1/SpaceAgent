package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.AccessTokenRevocation;
import com.spaceagent.platform.identity.domain.ConsumedRefreshToken;
import com.spaceagent.platform.identity.domain.IdentitySessionRepository;
import com.spaceagent.platform.identity.domain.RefreshTokenSession;
import com.spaceagent.platform.identity.domain.TenantRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative PostgreSQL identity-session and access-revocation persistence.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentitySessionRepository implements IdentitySessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresIdentitySessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override public Optional<String> refreshTokenOwner(String hash,Instant now){
        return jdbcTemplate.query("SELECT user_id FROM platform_refresh_tokens WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?",
                (rs,row)->rs.getString(1),hash,Timestamp.from(now)).stream().findFirst();
    }
    @Override public long lockAccessVersion(String userId){
        // Serialize version changes without blocking login-event FK KEY SHARE in its audit transaction.
        return jdbcTemplate.queryForObject("SELECT access_version FROM platform_users WHERE id=? FOR NO KEY UPDATE",Long.class,userId);
    }
    @Override public long accessVersion(String userId){
        return jdbcTemplate.query("SELECT access_version FROM platform_users WHERE id=?",(rs,row)->rs.getLong(1),userId).stream().findFirst().orElse(-1L);
    }
    @Override public void incrementAccessVersion(String userId){jdbcTemplate.update("UPDATE platform_users SET access_version=access_version+1 WHERE id=?",userId);}

    @Override
    public void saveRefreshToken(RefreshTokenSession session) {
        jdbcTemplate.update("""
                INSERT INTO platform_refresh_tokens (
                    token_hash, user_id, tenant_id, tenant_role, expires_at, created_at,session_id,access_version
                ) VALUES (?, ?, ?, ?, ?, ?,?,?)
                """,
                session.tokenHash(),
                session.userId(),
                session.tenantId(),
                session.tenantRole().name(),
                Timestamp.from(session.expiresAt()),
                Timestamp.from(session.createdAt()),session.sessionId(),session.accessVersion());
    }

    @Override
    public Optional<ConsumedRefreshToken> consumeRefreshToken(
            String tokenHash,
            String replacementHash,
            Instant consumedAt) {
        return jdbcTemplate.query("""
                UPDATE platform_refresh_tokens
                   SET revoked_at = ?, replaced_by_hash = ?
                 WHERE token_hash = ?
                   AND revoked_at IS NULL
                   AND expires_at > ?
                RETURNING user_id, tenant_id, tenant_role,session_id,access_version
                """,
                (rs, rowNum) -> new ConsumedRefreshToken(
                        rs.getString("user_id"),
                        rs.getString("tenant_id"),
                        TenantRole.valueOf(rs.getString("tenant_role")),rs.getObject("session_id",java.util.UUID.class),rs.getLong("access_version")),
                Timestamp.from(consumedAt),
                replacementHash,
                tokenHash,
                Timestamp.from(consumedAt)).stream().findFirst();
    }

    @Override
    public void revokeRefreshToken(String tokenHash, Instant revokedAt) {
        jdbcTemplate.update("""
                UPDATE platform_refresh_tokens
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE token_hash = ?
                """, Timestamp.from(revokedAt), tokenHash);
    }

    @Override
    public void revokeRefreshSession(String userId, UUID sessionId, Instant revokedAt) {
        jdbcTemplate.update("""
                UPDATE platform_refresh_tokens
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE user_id = ?
                   AND session_id = ?
                   AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), userId, sessionId);
    }

    @Override
    public void revokeAllRefreshTokens(String userId, Instant revokedAt) {
        jdbcTemplate.update("""
                UPDATE platform_refresh_tokens
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE user_id = ? AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), userId);
    }

    @Override
    public void saveAccessTokenRevocation(AccessTokenRevocation revocation) {
        jdbcTemplate.update("""
                INSERT INTO platform_access_token_revocations (
                    token_hash, user_id, expires_at, revoked_at
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (token_hash) DO NOTHING
                """,
                revocation.tokenHash(),
                revocation.userId(),
                Timestamp.from(revocation.expiresAt()),
                Timestamp.from(revocation.revokedAt()));
    }

    @Override
    public boolean isAccessTokenRevoked(String tokenHash, Instant checkedAt) {
        Boolean revoked = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM platform_access_token_revocations
                    WHERE token_hash = ? AND expires_at > ?
                )
                """, Boolean.class, tokenHash, Timestamp.from(checkedAt));
        return Boolean.TRUE.equals(revoked);
    }

    @Override
    public boolean isRefreshSessionActive(
            String userId, UUID sessionId, long accessVersion, Instant checkedAt) {
        Boolean active = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM platform_refresh_tokens refresh
                    JOIN platform_users user_record
                      ON user_record.id = refresh.user_id
                     AND user_record.access_version = refresh.access_version
                    WHERE refresh.user_id = ?
                      AND refresh.session_id = ?
                      AND refresh.access_version = ?
                      AND refresh.revoked_at IS NULL
                      AND refresh.expires_at > ?
                )
                """, Boolean.class, userId, sessionId, accessVersion, Timestamp.from(checkedAt));
        return Boolean.TRUE.equals(active);
    }
}
