package com.spaceagent.platform.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain persistence port for refresh rotation and access-token revocation.
 */
public interface IdentitySessionRepository {

    Optional<String> refreshTokenOwner(String tokenHash,Instant checkedAt);

    long lockAccessVersion(String userId);

    long accessVersion(String userId);

    void incrementAccessVersion(String userId);

    void saveRefreshToken(RefreshTokenSession session);

    Optional<ConsumedRefreshToken> consumeRefreshToken(
            String tokenHash,
            String replacementHash,
            Instant consumedAt);

    void revokeRefreshToken(String tokenHash, Instant revokedAt);

    void revokeRefreshSession(String userId, UUID sessionId, Instant revokedAt);

    void revokeAllRefreshTokens(String userId, Instant revokedAt);

    void saveAccessTokenRevocation(AccessTokenRevocation revocation);

    boolean isAccessTokenRevoked(String tokenHash, Instant checkedAt);

    boolean isRefreshSessionActive(
            String userId, UUID sessionId, long accessVersion, Instant checkedAt);
}
