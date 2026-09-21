package com.spaceagent.platform.identity.infrastructure.memory;

import com.spaceagent.platform.identity.domain.AccessTokenRevocation;
import com.spaceagent.platform.identity.domain.ConsumedRefreshToken;
import com.spaceagent.platform.identity.domain.IdentitySessionRepository;
import com.spaceagent.platform.identity.domain.RefreshTokenSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session lifecycle used only by tests and local scaffolding.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryIdentitySessionRepository implements IdentitySessionRepository {

    private final Map<String, RefreshTokenSession> refreshTokens = new ConcurrentHashMap<>();
    private final Set<String> revokedRefreshTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> revokedRefreshSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, AccessTokenRevocation> accessRevocations = new ConcurrentHashMap<>();
    private final Map<String, Long> accessVersions = new ConcurrentHashMap<>();

    @Override
    public Optional<String> refreshTokenOwner(String tokenHash, Instant checkedAt) {
        return Optional.ofNullable(refreshTokens.get(tokenHash))
                .filter(s -> !revokedRefreshTokens.contains(tokenHash) && s.expiresAt().isAfter(checkedAt))
                .map(RefreshTokenSession::userId);
    }

    @Override
    public long lockAccessVersion(String userId) { return accessVersion(userId); }

    @Override
    public long accessVersion(String userId) { return accessVersions.getOrDefault(userId, 0L); }

    @Override
    public void incrementAccessVersion(String userId) { accessVersions.merge(userId, 1L, Long::sum); }

    @Override
    public synchronized void saveRefreshToken(RefreshTokenSession session) {
        refreshTokens.put(session.tokenHash(), session);
        if (revokedRefreshSessions.contains(sessionKey(session.userId(), session.sessionId()))) {
            revokedRefreshTokens.add(session.tokenHash());
        }
    }

    @Override
    public synchronized Optional<ConsumedRefreshToken> consumeRefreshToken(
            String tokenHash,
            String replacementHash,
            Instant consumedAt) {
        RefreshTokenSession session = refreshTokens.get(tokenHash);
        if (session == null
                || revokedRefreshTokens.contains(tokenHash)
                || !session.expiresAt().isAfter(consumedAt)) {
            return Optional.empty();
        }
        revokedRefreshTokens.add(tokenHash);
        return Optional.of(new ConsumedRefreshToken(
                session.userId(),
                session.tenantId(),
                session.tenantRole(), session.sessionId(), session.accessVersion()));
    }

    @Override
    public void revokeRefreshToken(String tokenHash, Instant revokedAt) {
        if (refreshTokens.containsKey(tokenHash)) {
            revokedRefreshTokens.add(tokenHash);
        }
    }

    @Override
    public synchronized void revokeRefreshSession(String userId, UUID sessionId, Instant revokedAt) {
        revokedRefreshSessions.add(sessionKey(userId, sessionId));
        refreshTokens.values().stream()
                .filter(session -> userId.equals(session.userId()) && sessionId.equals(session.sessionId()))
                .map(RefreshTokenSession::tokenHash)
                .forEach(revokedRefreshTokens::add);
    }

    @Override
    public void revokeAllRefreshTokens(String userId, Instant revokedAt) {
        refreshTokens.values().stream()
                .filter(session -> userId.equals(session.userId()))
                .map(RefreshTokenSession::tokenHash)
                .forEach(revokedRefreshTokens::add);
    }

    @Override
    public void saveAccessTokenRevocation(AccessTokenRevocation revocation) {
        accessRevocations.put(revocation.tokenHash(), revocation);
    }

    @Override
    public boolean isAccessTokenRevoked(String tokenHash, Instant checkedAt) {
        AccessTokenRevocation revocation = accessRevocations.get(tokenHash);
        return revocation != null && revocation.expiresAt().isAfter(checkedAt);
    }

    @Override
    public boolean isRefreshSessionActive(
            String userId, UUID sessionId, long accessVersion, Instant checkedAt) {
        if (accessVersion(userId) != accessVersion) return false;
        if (revokedRefreshSessions.contains(sessionKey(userId, sessionId))) return false;
        return refreshTokens.values().stream()
                .anyMatch(session -> userId.equals(session.userId())
                        && sessionId.equals(session.sessionId())
                        && accessVersion == session.accessVersion()
                        && !revokedRefreshTokens.contains(session.tokenHash())
                        && session.expiresAt().isAfter(checkedAt));
    }

    private static String sessionKey(String userId, UUID sessionId) {
        return userId + "\n" + sessionId;
    }
}
