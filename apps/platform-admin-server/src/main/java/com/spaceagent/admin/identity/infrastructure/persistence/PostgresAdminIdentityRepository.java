package com.spaceagent.admin.identity.infrastructure.persistence;

import com.spaceagent.admin.identity.domain.AdminAuthenticationRecord;
import com.spaceagent.admin.identity.domain.AdminIdentityRepository;
import com.spaceagent.admin.identity.domain.AdminMfaChallenge;
import com.spaceagent.admin.identity.domain.AdminPrincipalStatus;
import com.spaceagent.admin.identity.domain.AdminRole;
import com.spaceagent.admin.identity.domain.AdminSession;
import com.spaceagent.admin.identity.domain.SystemAdministrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresAdminIdentityRepository implements AdminIdentityRepository {
    private static final String AUTHENTICATION_SELECT = """
            SELECT principal.id, principal.login_name, principal.display_name,
                   principal.status, principal.admin_role, principal.credential_version,
                   principal.mfa_required, principal.must_change_password,
                   principal.last_successful_login_at,
                   principal.created_at, principal.updated_at,
                   credential.password_hash
            FROM admin_principals principal
            JOIN admin_credentials credential ON credential.principal_id = principal.id
            """;

    private final JdbcTemplate jdbc;

    public PostgresAdminIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasAnyPrincipal() {
        Boolean value = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM admin_principals)", Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    @Override
    @Transactional
    public void bootstrap(
            SystemAdministrator principal,
            String passwordHash,
            UUID factorId,
            String factorCiphertext,
            List<RecoveryCodeRow> recoveryCodes,
            Instant completedAt) {
        Boolean completed = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM admin_bootstrap_state WHERE singleton_id = 1)",
                Boolean.class);
        if (Boolean.TRUE.equals(completed) || hasAnyPrincipal()) {
            throw new IllegalStateException("Administrator bootstrap has already completed");
        }
        jdbc.update("""
                INSERT INTO admin_principals (
                    id, login_name, display_name, status, admin_role,
                    credential_version, mfa_required, must_change_password,
                    last_successful_login_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, principal.id(), principal.loginName(), principal.displayName(),
                principal.status().name(), principal.role().name(), principal.credentialVersion(),
                principal.mfaRequired(), principal.mustChangePassword(),
                timestamp(principal.lastSuccessfulLoginAt()),
                Timestamp.from(principal.createdAt()), Timestamp.from(principal.updatedAt()));
        jdbc.update("""
                INSERT INTO admin_credentials (principal_id, password_hash, changed_at, created_at)
                VALUES (?, ?, ?, ?)
                """, principal.id(), passwordHash, Timestamp.from(completedAt),
                Timestamp.from(completedAt));
        if (factorCiphertext != null) jdbc.update("""
                INSERT INTO admin_mfa_factors (
                    id, principal_id, factor_type, secret_ciphertext, status,
                    verified_at, created_at, updated_at
                ) VALUES (?, ?, 'TOTP', ?, 'ACTIVE', ?, ?, ?)
                """, factorId, principal.id(), factorCiphertext, Timestamp.from(completedAt),
                Timestamp.from(completedAt), Timestamp.from(completedAt));
        insertRecoveryCodes(principal.id(), recoveryCodes);
        jdbc.update("""
                INSERT INTO admin_bootstrap_state (singleton_id, principal_id, completed_at)
                VALUES (1, ?, ?)
                """, principal.id(), Timestamp.from(completedAt));
    }

    @Override
    public Optional<AdminAuthenticationRecord> findAuthenticationByLogin(String loginName) {
        return jdbc.query(
                AUTHENTICATION_SELECT + " WHERE lower(principal.login_name) = lower(?)",
                this::mapAuthentication,
                loginName).stream().findFirst();
    }

    @Override
    public void lockConfiguredAccount() {
        jdbc.execute("SELECT pg_advisory_xact_lock(921344201)");
    }

    @Override
    public Optional<AdminAuthenticationRecord> findSingletonAuthentication() {
        return jdbc.query(AUTHENTICATION_SELECT + " WHERE principal.singleton_slot = 1 FOR UPDATE OF principal, credential",
                this::mapAuthentication).stream().findFirst();
    }

    @Override
    public Optional<AdminAuthenticationRecord> findAuthenticationByLoginForUpdate(String loginName) {
        return jdbc.query(AUTHENTICATION_SELECT + " WHERE lower(principal.login_name) = lower(?) FOR UPDATE OF principal, credential",
                this::mapAuthentication, loginName).stream().findFirst();
    }

    @Override
    public void synchronizeConfiguredAccount(UUID id, String login, String display, String passwordHash, Instant at) {
        jdbc.update("""
                UPDATE admin_principals SET login_name=?, display_name=?, status='ACTIVE',
                    mfa_required=FALSE, must_change_password=FALSE, credential_version=credential_version+1,
                    updated_at=? WHERE id=? AND singleton_slot=1
                """, login, display, Timestamp.from(at), id);
        jdbc.update("UPDATE admin_credentials SET password_hash=?,changed_at=? WHERE principal_id=?",
                passwordHash, Timestamp.from(at), id);
        revokeAllSessions(id, at);
        jdbc.update("UPDATE admin_mfa_challenges SET consumed_at=COALESCE(consumed_at,?) WHERE principal_id=?", Timestamp.from(at), id);
        jdbc.update("UPDATE admin_mfa_factors SET status='REVOKED',updated_at=? WHERE principal_id=?", Timestamp.from(at), id);
    }

    @Override
    public Optional<AdminAuthenticationRecord> findAuthenticationByPrincipalId(UUID principalId) {
        return jdbc.query(
                AUTHENTICATION_SELECT + " WHERE principal.id = ?",
                this::mapAuthentication,
                principalId).stream().findFirst();
    }

    @Override
    public Optional<SystemAdministrator> findPrincipalById(UUID principalId) {
        return jdbc.query("""
                SELECT id, login_name, display_name, status, admin_role,
                       credential_version, mfa_required, must_change_password,
                       last_successful_login_at,
                       created_at, updated_at
                FROM admin_principals WHERE id = ?
                """, this::mapPrincipal, principalId).stream().findFirst();
    }

    @Override
    public PageRows<PrincipalRow> pagePrincipals(
            int offset, int limit, String query, AdminPrincipalStatus status) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> filters = new ArrayList<>();
        if (query != null) {
            where.append(" AND (lower(principal.login_name) LIKE ? OR lower(principal.display_name) LIKE ? OR principal.id::text = ?) ");
            String like = "%" + query.toLowerCase(java.util.Locale.ROOT) + "%";
            filters.add(like); filters.add(like); filters.add(query);
        }
        if (status != null) { where.append(" AND principal.status = ? "); filters.add(status.name()); }
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(limit); arguments.add(offset);
        var items = jdbc.query("""
                SELECT principal.id, principal.login_name, principal.display_name,
                       principal.status, principal.admin_role, principal.credential_version,
                       principal.mfa_required, principal.must_change_password,
                       principal.last_successful_login_at, principal.created_at, principal.updated_at,
                       (SELECT count(*) FROM admin_sessions session
                         WHERE session.principal_id = principal.id AND session.revoked_at IS NULL
                           AND session.expires_at > clock_timestamp()) active_sessions,
                       (SELECT count(*) FROM admin_recovery_codes code
                         WHERE code.principal_id = principal.id AND code.consumed_at IS NULL)
                         remaining_recovery_codes
                FROM admin_principals principal
                """ + where + " ORDER BY principal.created_at, principal.id LIMIT ? OFFSET ?",
                (rs, row) -> new PrincipalRow(mapPrincipal(rs, row),
                        rs.getLong("active_sessions"), rs.getLong("remaining_recovery_codes")),
                arguments.toArray());
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM admin_principals principal " + where,
                Long.class, filters.toArray());
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public boolean replaceCredentials(
            UUID principalId, String passwordHash, UUID factorId,
            String factorCiphertext, List<RecoveryCodeRow> recoveryCodes, Instant at) {
        int updated = jdbc.update("""
                UPDATE admin_principals
                   SET credential_version = credential_version + 1,
                       must_change_password = TRUE, updated_at = ?
                 WHERE id = ? AND status <> 'DELETED'
                """, Timestamp.from(at), principalId);
        if (updated != 1) return false;
        jdbc.update("UPDATE admin_credentials SET password_hash = ?, changed_at = ? WHERE principal_id = ?",
                passwordHash, Timestamp.from(at), principalId);
        jdbc.update("DELETE FROM admin_mfa_factors WHERE principal_id = ?", principalId);
        insertFactor(principalId, factorId, factorCiphertext, at);
        jdbc.update("DELETE FROM admin_recovery_codes WHERE principal_id = ?", principalId);
        insertRecoveryCodes(principalId, recoveryCodes);
        revokeAllSessions(principalId, at);
        return true;
    }

    @Override
    public boolean changePassword(UUID principalId, String passwordHash, Instant at) {
        int updated = jdbc.update("""
                UPDATE admin_principals
                   SET credential_version = credential_version + 1,
                       must_change_password = FALSE, updated_at = ?
                 WHERE id = ? AND status = 'ACTIVE'
                """, Timestamp.from(at), principalId);
        if (updated != 1) return false;
        jdbc.update("UPDATE admin_credentials SET password_hash = ?, changed_at = ? WHERE principal_id = ?",
                passwordHash, Timestamp.from(at), principalId);
        revokeAllSessions(principalId, at);
        return true;
    }

    @Override
    public long countRecentFailedLoginAttempts(String subjectHash, Instant since) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM admin_login_attempts
                WHERE subject_hash = ? AND succeeded = FALSE AND occurred_at >= ?
                """, Long.class, subjectHash, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    @Override
    public void recordLoginAttempt(
            String subjectHash,
            boolean succeeded,
            String safeErrorCode,
            Instant at) {
        jdbc.update("""
                INSERT INTO admin_login_attempts (
                    subject_hash, succeeded, safe_error_code, occurred_at
                ) VALUES (?, ?, ?, ?)
                """, subjectHash, succeeded, safeErrorCode, Timestamp.from(at));
    }

    @Override
    public void saveMfaChallenge(AdminMfaChallenge challenge) {
        jdbc.update("""
                INSERT INTO admin_mfa_challenges (
                    token_hash, principal_id, expires_at, attempt_count, consumed_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, challenge.tokenHash(), challenge.principalId(),
                Timestamp.from(challenge.expiresAt()), challenge.attemptCount(),
                timestamp(challenge.consumedAt()), Timestamp.from(challenge.createdAt()));
    }

    @Override
    public Optional<AdminMfaChallenge> findMfaChallengeForUpdate(String tokenHash) {
        return jdbc.query("""
                SELECT token_hash, principal_id, expires_at, attempt_count, consumed_at, created_at
                FROM admin_mfa_challenges WHERE token_hash = ? FOR UPDATE
                """, (rs, row) -> new AdminMfaChallenge(
                        rs.getString("token_hash"), rs.getObject("principal_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(), rs.getInt("attempt_count"),
                        instant(rs.getTimestamp("consumed_at")),
                        rs.getTimestamp("created_at").toInstant()), tokenHash).stream().findFirst();
    }

    @Override
    public void incrementMfaChallengeAttempt(String tokenHash) {
        jdbc.update("""
                UPDATE admin_mfa_challenges
                   SET attempt_count = LEAST(attempt_count + 1, 10)
                 WHERE token_hash = ? AND consumed_at IS NULL
                """, tokenHash);
    }

    @Override
    public boolean acceptMfaTimeStep(UUID factorId, long timeStep, Instant at) {
        return jdbc.update("""
                UPDATE admin_mfa_factors
                   SET last_accepted_time_step = ?, updated_at = ?
                 WHERE id = ? AND status = 'ACTIVE'
                   AND (last_accepted_time_step IS NULL OR last_accepted_time_step < ?)
                """, timeStep, Timestamp.from(at), factorId, timeStep) == 1;
    }

    @Override
    public boolean consumeMfaChallenge(String tokenHash, Instant at) {
        return jdbc.update("""
                UPDATE admin_mfa_challenges SET consumed_at = ?
                 WHERE token_hash = ? AND consumed_at IS NULL
                """, Timestamp.from(at), tokenHash) == 1;
    }

    @Override
    public void updateLastSuccessfulLogin(UUID principalId, Instant at) {
        jdbc.update("""
                UPDATE admin_principals
                   SET last_successful_login_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'ACTIVE'
                """, Timestamp.from(at), Timestamp.from(at), principalId);
    }

    @Override
    public void saveSession(AdminSession session) {
        jdbc.update("""
                INSERT INTO admin_sessions (
                    id, principal_id, refresh_token_hash, csrf_token_hash,
                    credential_version, authenticated_at, expires_at, revoked_at, replaced_by_hash,
                    created_at, last_rotated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, session.id(), session.principalId(), session.refreshTokenHash(),
                session.csrfTokenHash(), session.credentialVersion(),
                Timestamp.from(session.authenticatedAt()), Timestamp.from(session.expiresAt()),
                timestamp(session.revokedAt()),
                session.replacedByHash(), Timestamp.from(session.createdAt()),
                Timestamp.from(session.lastRotatedAt()));
    }

    @Override
    public Optional<AdminSession> findSessionByRefreshHashForUpdate(String refreshHash) {
        return jdbc.query("""
                SELECT id, principal_id, refresh_token_hash, csrf_token_hash,
                       credential_version, authenticated_at, expires_at, revoked_at, replaced_by_hash,
                       created_at, last_rotated_at
                FROM admin_sessions WHERE refresh_token_hash = ? FOR UPDATE
                """, this::mapSession, refreshHash).stream().findFirst();
    }

    @Override
    public Optional<AdminSession> findSessionById(UUID sessionId) {
        return jdbc.query("""
                SELECT id, principal_id, refresh_token_hash, csrf_token_hash,
                       credential_version, authenticated_at, expires_at, revoked_at, replaced_by_hash,
                       created_at, last_rotated_at
                FROM admin_sessions WHERE id = ?
                """, this::mapSession, sessionId).stream().findFirst();
    }

    @Override
    public PageRows<AdminSession> pageSessions(UUID principalId, int offset, int limit) {
        var items = jdbc.query("""
                SELECT id, principal_id, refresh_token_hash, csrf_token_hash,
                       credential_version, authenticated_at, expires_at, revoked_at, replaced_by_hash,
                       created_at, last_rotated_at
                  FROM admin_sessions WHERE principal_id = ?
                 ORDER BY created_at DESC, id LIMIT ? OFFSET ?
                """, this::mapSession, principalId, limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM admin_sessions WHERE principal_id = ?", Long.class, principalId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    @Override
    public boolean revokeSession(UUID sessionId, String replacementHash, Instant at) {
        return jdbc.update("""
                UPDATE admin_sessions
                   SET revoked_at = COALESCE(revoked_at, ?), replaced_by_hash = ?
                 WHERE id = ? AND revoked_at IS NULL
                """, Timestamp.from(at), replacementHash, sessionId) == 1;
    }

    @Override
    public long revokeAllSessions(UUID principalId, Instant at) {
        return jdbc.update("""
                UPDATE admin_sessions SET revoked_at = ?
                 WHERE principal_id = ? AND revoked_at IS NULL
                """, Timestamp.from(at), principalId);
    }

    @Override
    public boolean consumeRecoveryCode(UUID principalId, String codeHash, Instant at) {
        return jdbc.update("""
                UPDATE admin_recovery_codes SET consumed_at = ?
                 WHERE principal_id = ? AND code_hash = ? AND consumed_at IS NULL
                """, Timestamp.from(at), principalId, codeHash) == 1;
    }

    @Override
    public void replaceRecoveryCodes(UUID principalId, List<RecoveryCodeRow> recoveryCodes) {
        jdbc.update("DELETE FROM admin_recovery_codes WHERE principal_id = ?", principalId);
        insertRecoveryCodes(principalId, recoveryCodes);
    }

    @Override
    public boolean breakGlassRequestApplied(String requestHash) {
        Boolean value = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM admin_break_glass_events WHERE request_hash = ?)",
                Boolean.class, requestHash);
        return Boolean.TRUE.equals(value);
    }

    @Override
    public boolean activateSingletonPrincipal(UUID principalId, Instant at) {
        return jdbc.update("""
                UPDATE admin_principals SET status = 'ACTIVE', updated_at = ?
                 WHERE id = ? AND singleton_slot = 1 AND status <> 'DELETED'
                """, Timestamp.from(at), principalId) == 1;
    }

    @Override
    public void recordBreakGlassEvent(String requestHash, UUID principalId, Instant at) {
        jdbc.update("""
                INSERT INTO admin_break_glass_events(request_hash,principal_id,applied_at)
                VALUES (?,?,?)
                """, requestHash, principalId, Timestamp.from(at));
    }

    private AdminAuthenticationRecord mapAuthentication(ResultSet rs, int row) throws SQLException {
        return new AdminAuthenticationRecord(
                mapPrincipal(rs, row), rs.getString("password_hash"));
    }

    private SystemAdministrator mapPrincipal(ResultSet rs, int row) throws SQLException {
        return new SystemAdministrator(
                rs.getObject("id", UUID.class), rs.getString("login_name"),
                rs.getString("display_name"),
                AdminPrincipalStatus.valueOf(rs.getString("status")),
                AdminRole.valueOf(rs.getString("admin_role")),
                rs.getLong("credential_version"), rs.getBoolean("mfa_required"),
                rs.getBoolean("must_change_password"),
                instant(rs.getTimestamp("last_successful_login_at")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private AdminSession mapSession(ResultSet rs, int row) throws SQLException {
        return new AdminSession(
                rs.getObject("id", UUID.class), rs.getObject("principal_id", UUID.class),
                rs.getString("refresh_token_hash"), rs.getString("csrf_token_hash"),
                rs.getLong("credential_version"), rs.getTimestamp("authenticated_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                instant(rs.getTimestamp("revoked_at")), rs.getString("replaced_by_hash"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_rotated_at").toInstant());
    }

    private void insertFactor(UUID principalId, UUID factorId, String ciphertext, Instant at) {
        jdbc.update("""
                INSERT INTO admin_mfa_factors(
                    id,principal_id,factor_type,secret_ciphertext,status,last_accepted_time_step,
                    verified_at,created_at,updated_at)
                VALUES (?,?,'TOTP',?,'ACTIVE',NULL,?,?,?)
                """, factorId, principalId, ciphertext, Timestamp.from(at), Timestamp.from(at),
                Timestamp.from(at));
    }

    private void insertRecoveryCodes(UUID principalId, List<RecoveryCodeRow> codes) {
        for (RecoveryCodeRow code : codes) {
            jdbc.update("""
                    INSERT INTO admin_recovery_codes(id,principal_id,code_hash,created_at)
                    VALUES (?,?,?,?)
                    """, code.id(), principalId, code.codeHash(), Timestamp.from(code.createdAt()));
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
