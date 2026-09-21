package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.IdentityCredentialRepository;
import com.spaceagent.platform.identity.domain.UserCredential;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Authoritative PostgreSQL credential persistence for platform-server identity.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentityCredentialRepository implements IdentityCredentialRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresIdentityCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserCredential> findByUsername(String username) {
        return jdbcTemplate.query("""
                SELECT user_id, username, password_hash, created_at, updated_at
                FROM platform_user_credentials
                WHERE username = ?
                """, this::map, username).stream().findFirst();
    }

    @Override
    public Optional<UserCredential> findByUserId(String userId) {
        return jdbcTemplate.query("""
                SELECT user_id, username, password_hash, created_at, updated_at
                FROM platform_user_credentials
                WHERE user_id = ?
                """, this::map, userId).stream().findFirst();
    }

    @Override
    public void save(UserCredential credential) {
        jdbcTemplate.update("""
                INSERT INTO platform_user_credentials (
                    user_id, username, password_hash, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    username = EXCLUDED.username,
                    password_hash = EXCLUDED.password_hash,
                    updated_at = EXCLUDED.updated_at
                """,
                credential.userId(),
                credential.username(),
                credential.passwordHash(),
                Timestamp.from(credential.createdAt()),
                Timestamp.from(credential.updatedAt()));
    }

    @Override
    public void updatePassword(String userId, String passwordHash) {
        jdbcTemplate.update("""
                UPDATE platform_user_credentials
                SET password_hash = ?, updated_at = ?
                WHERE user_id = ?
                """, passwordHash, Timestamp.from(Instant.now()), userId);
    }

    private UserCredential map(ResultSet rs, int rowNum) throws SQLException {
        return new UserCredential(
                rs.getString("user_id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
