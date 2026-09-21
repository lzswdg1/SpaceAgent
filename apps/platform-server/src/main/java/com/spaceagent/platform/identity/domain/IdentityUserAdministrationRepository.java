package com.spaceagent.platform.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdentityUserAdministrationRepository {
    Optional<UserLifecycleRow> findUser(String userId);

    boolean updateStatus(String userId, String expectedStatus, String newStatus, Instant at);

    void saveActivationToken(ActivationToken token);

    Optional<ActivationToken> findActivationTokenForUpdate(String tokenHash);

    boolean consumeActivationToken(UUID tokenId, Instant at);

    void savePasswordResetToken(PasswordResetToken token);
    Optional<PasswordResetToken> findPasswordResetToken(String tokenHash);
    boolean consumePasswordResetToken(UUID tokenId, Instant at);

    record PasswordResetToken(UUID id, String userId, String tokenHash, Instant expiresAt,
                              Instant consumedAt, Instant createdAt, UUID createdBy, long accessVersion) {
        public boolean usableAt(Instant now) { return consumedAt == null && expiresAt.isAfter(now); }
    }

    record UserLifecycleRow(String id, String tenantId, String externalId, String displayName,
                            String status, Instant createdAt, Instant updatedAt) {
    }

    record ActivationToken(UUID id, String userId, String tokenHash, Instant expiresAt,
                           Instant consumedAt, Instant createdAt, UUID createdBy) {
        public boolean usableAt(Instant now) {
            return consumedAt == null && expiresAt.isAfter(now);
        }
    }
}
