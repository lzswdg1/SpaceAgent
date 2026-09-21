package com.spaceagent.platform.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public identity-session lifecycle used by the platform HTTP integration edge.
 */
public interface IdentitySessionApplicationApi {

    IdentitySessionView openSession(AuthenticatedIdentityView identity);

    IdentitySessionView openSessionForOrganization(String userId, String organizationId);

    IdentitySessionView rotateRefreshToken(String refreshToken);

    void revokeRefreshToken(String refreshToken);

    void revokeAllRefreshTokens(String userId);

    void revokeAccessToken(String accessToken, String userId, Instant expiresAt);

    void revokeSession(SessionRevocationCommand command);

    boolean isAccessTokenRevoked(String accessToken);

    boolean isAccessVersionCurrent(String userId,long version);

    boolean isSessionActive(String userId, UUID sessionId, long accessVersion);

    record SessionRevocationCommand(
            String userId,
            UUID sessionId,
            String accessToken,
            Instant accessExpiresAt,
            String legacyRefreshToken) {
    }
}
