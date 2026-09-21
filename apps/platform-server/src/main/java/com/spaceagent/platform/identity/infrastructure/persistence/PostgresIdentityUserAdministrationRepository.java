package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.IdentityUserAdministrationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentityUserAdministrationRepository
        implements IdentityUserAdministrationRepository {
    private final JdbcTemplate jdbc;
    public PostgresIdentityUserAdministrationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<UserLifecycleRow> findUser(String userId) {
        return jdbc.query("""
                SELECT id, tenant_id, external_id, display_name, status, created_at, updated_at
                FROM platform_users WHERE id = ?
                """, (rs, row) -> new UserLifecycleRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("external_id"), rs.getString("display_name"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                userId).stream().findFirst();
    }

    @Override
    public boolean updateStatus(String userId, String expectedStatus, String newStatus, Instant at) {
        return jdbc.update("""
                UPDATE platform_users SET status = ?, updated_at = ?
                 WHERE id = ? AND status = ?
                """, newStatus, Timestamp.from(at), userId, expectedStatus) == 1;
    }

    @Override
    public void saveActivationToken(ActivationToken token) {
        jdbc.update("""
                INSERT INTO platform_user_activation_tokens (
                    id, user_id, token_hash, expires_at, consumed_at, created_at, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, token.id(), token.userId(), token.tokenHash(), Timestamp.from(token.expiresAt()),
                token.consumedAt() == null ? null : Timestamp.from(token.consumedAt()),
                Timestamp.from(token.createdAt()), token.createdBy());
    }

    @Override
    public Optional<ActivationToken> findActivationTokenForUpdate(String tokenHash) {
        return jdbc.query("""
                SELECT id, user_id, token_hash, expires_at, consumed_at, created_at, created_by
                FROM platform_user_activation_tokens WHERE token_hash = ? FOR UPDATE
                """, (rs, row) -> new ActivationToken(rs.getObject("id", UUID.class),
                rs.getString("user_id"), rs.getString("token_hash"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("created_by", UUID.class)),
                tokenHash).stream().findFirst();
    }

    @Override public void savePasswordResetToken(PasswordResetToken token) {
        jdbc.update("UPDATE platform_user_password_reset_tokens SET consumed_at=? WHERE user_id=? AND consumed_at IS NULL",
                Timestamp.from(token.createdAt()), token.userId());
        jdbc.update("""
                INSERT INTO platform_user_password_reset_tokens(id,user_id,token_hash,expires_at,created_at,created_by,access_version)
                VALUES (?,?,?,?,?,?,?)
                """, token.id(), token.userId(), token.tokenHash(), Timestamp.from(token.expiresAt()),
                Timestamp.from(token.createdAt()), token.createdBy(), token.accessVersion());
    }

    @Override public Optional<PasswordResetToken> findPasswordResetToken(String hash) {
        return jdbc.query("SELECT * FROM platform_user_password_reset_tokens WHERE token_hash=?",
                (rs,row) -> new PasswordResetToken(rs.getObject("id", UUID.class), rs.getString("user_id"),
                        rs.getString("token_hash"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(), rs.getObject("created_by", UUID.class), rs.getLong("access_version")), hash)
                .stream().findFirst();
    }

    @Override public boolean consumePasswordResetToken(UUID id, Instant now) {
        return jdbc.update("UPDATE platform_user_password_reset_tokens SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND expires_at>?",
                Timestamp.from(now), id, Timestamp.from(now)) == 1;
    }

    @Override
    public boolean consumeActivationToken(UUID tokenId, Instant at) {
        return jdbc.update("""
                UPDATE platform_user_activation_tokens SET consumed_at = ?
                 WHERE id = ? AND consumed_at IS NULL AND expires_at > ?
                """, Timestamp.from(at), tokenId, Timestamp.from(at)) == 1;
    }
}
