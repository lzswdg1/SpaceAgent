package com.spaceagent.admin.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminIdentityRepository {
    boolean hasAnyPrincipal();

    void lockConfiguredAccount();

    Optional<AdminAuthenticationRecord> findSingletonAuthentication();

    Optional<AdminAuthenticationRecord> findAuthenticationByLoginForUpdate(String loginName);

    void synchronizeConfiguredAccount(UUID id, String loginName, String displayName, String passwordHash, Instant at);

    void bootstrap(
            SystemAdministrator principal,
            String passwordHash,
            UUID factorId,
            String factorCiphertext,
            List<RecoveryCodeRow> recoveryCodes,
            Instant completedAt);

    Optional<AdminAuthenticationRecord> findAuthenticationByLogin(String loginName);

    Optional<AdminAuthenticationRecord> findAuthenticationByPrincipalId(UUID principalId);

    Optional<SystemAdministrator> findPrincipalById(UUID principalId);

    PageRows<PrincipalRow> pagePrincipals(
            int offset, int limit, String query, AdminPrincipalStatus status);

    boolean replaceCredentials(
            UUID principalId, String passwordHash, UUID factorId,
            String factorCiphertext, List<RecoveryCodeRow> recoveryCodes, Instant at);

    boolean changePassword(UUID principalId, String passwordHash, Instant at);

    long countRecentFailedLoginAttempts(String subjectHash, Instant since);

    void recordLoginAttempt(String subjectHash, boolean succeeded, String safeErrorCode, Instant at);

    void saveMfaChallenge(AdminMfaChallenge challenge);

    Optional<AdminMfaChallenge> findMfaChallengeForUpdate(String tokenHash);

    void incrementMfaChallengeAttempt(String tokenHash);

    boolean acceptMfaTimeStep(UUID factorId, long timeStep, Instant at);

    boolean consumeMfaChallenge(String tokenHash, Instant at);

    void updateLastSuccessfulLogin(UUID principalId, Instant at);

    void saveSession(AdminSession session);

    Optional<AdminSession> findSessionByRefreshHashForUpdate(String refreshHash);

    Optional<AdminSession> findSessionById(UUID sessionId);

    PageRows<AdminSession> pageSessions(UUID principalId, int offset, int limit);

    boolean revokeSession(UUID sessionId, String replacementHash, Instant at);

    long revokeAllSessions(UUID principalId, Instant at);

    boolean consumeRecoveryCode(UUID principalId, String codeHash, Instant at);

    void replaceRecoveryCodes(UUID principalId, List<RecoveryCodeRow> recoveryCodes);

    boolean breakGlassRequestApplied(String requestHash);

    boolean activateSingletonPrincipal(UUID principalId, Instant at);

    void recordBreakGlassEvent(String requestHash, UUID principalId, Instant at);

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record PrincipalRow(SystemAdministrator principal, long activeSessions,
                        long remainingRecoveryCodes) {
    }

    record RecoveryCodeRow(UUID id, String codeHash, Instant createdAt) {
    }
}
