package com.spaceagent.platform.identity.domain;

import java.time.Instant;
import java.util.UUID;

public interface IdentityPresenceRepository {
    boolean heartbeat(UUID sessionId, String userId, String tenantId, long accessVersion,
                      String accessHash, String clientType, Instant now, Instant expiresAt);
    void leave(UUID sessionId, String userId, Instant now);
    int sweepExpired(Instant expiredBefore, int limit);
    Counts counts(Instant now);
    record Counts(long users, long sessions, long observedSessions) { }
}
