package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.domain.IdentityActivityRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentityActivityRepository implements IdentityActivityRepository {
    private final JdbcTemplate jdbc;

    public PostgresIdentityActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordLoginEvent(
            UUID id,
            String userId,
            String subjectHash,
            boolean succeeded,
            String clientType,
            String ipHash,
            String userAgentHash,
            String safeErrorCode,
            Instant occurredAt) {
        jdbc.update("""
                INSERT INTO platform_auth_events (
                    id, user_id, subject_hash, event_type, client_type,
                    ip_hash, user_agent_hash, safe_error_code, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, subjectHash, succeeded ? "LOGIN_SUCCEEDED" : "LOGIN_FAILED",
                clientType, ipHash, userAgentHash, safeErrorCode, Timestamp.from(occurredAt));
    }

    @Override
    public void recordSuccessfulLogin(
            String userId,
            String clientType,
            String ipHash,
            String userAgentHash,
            Instant at) {
        jdbc.update("""
                INSERT INTO platform_user_activity (
                    user_id, last_login_at, last_seen_at, successful_login_count,
                    last_client_type, last_ip_hash, last_user_agent_hash, updated_at
                ) VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    last_login_at = EXCLUDED.last_login_at,
                    last_seen_at = EXCLUDED.last_seen_at,
                    successful_login_count = platform_user_activity.successful_login_count + 1,
                    last_client_type = EXCLUDED.last_client_type,
                    last_ip_hash = COALESCE(EXCLUDED.last_ip_hash, platform_user_activity.last_ip_hash),
                    last_user_agent_hash = COALESCE(
                        EXCLUDED.last_user_agent_hash, platform_user_activity.last_user_agent_hash),
                    updated_at = EXCLUDED.updated_at
                """, userId, Timestamp.from(at), Timestamp.from(at), clientType,
                ipHash, userAgentHash, Timestamp.from(at));
    }

    @Override
    public void markSeenIfDue(
            String userId,
            String clientType,
            String ipHash,
            String userAgentHash,
            Instant at,
            Instant updateBefore) {
        jdbc.update("""
                INSERT INTO platform_user_activity (
                    user_id, last_seen_at, successful_login_count, last_client_type,
                    last_ip_hash, last_user_agent_hash, updated_at
                ) VALUES (?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    last_seen_at = EXCLUDED.last_seen_at,
                    last_client_type = EXCLUDED.last_client_type,
                    last_ip_hash = EXCLUDED.last_ip_hash,
                    last_user_agent_hash = EXCLUDED.last_user_agent_hash,
                    updated_at = EXCLUDED.updated_at
                WHERE platform_user_activity.last_seen_at IS NULL
                   OR platform_user_activity.last_seen_at < ?
                """, userId, Timestamp.from(at), clientType, ipHash, userAgentHash,
                Timestamp.from(at), Timestamp.from(updateBefore));
    }

    @Override
    public boolean isUserActive(String userId) {
        return jdbc.query(
                "SELECT status = 'ACTIVE' AS active FROM platform_users WHERE id = ?",
                (rs, row) -> rs.getBoolean("active"), userId).stream().findFirst().orElse(false);
    }
}
