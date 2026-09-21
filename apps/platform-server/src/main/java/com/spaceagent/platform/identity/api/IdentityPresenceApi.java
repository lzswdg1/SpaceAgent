package com.spaceagent.platform.identity.api;

import java.time.Instant;
import java.util.UUID;

/** Heartbeats attest authenticated sessions, not physical devices or human attention. */
public interface IdentityPresenceApi {
    Lease heartbeat(Heartbeat command);
    void leave(UUID sessionId, String userId);
    Summary summary();

    record Heartbeat(UUID sessionId, String userId, String tenantId, long accessVersion,
                     String accessTokenHash, String clientType, Instant accessExpiresAt) { }
    record Lease(UUID sessionId, Instant observedAt, Instant expiresAt, int recommendedIntervalSeconds) { }
    record Summary(Long onlineUsers, Long onlineSessions, long observedSessionCount,
                   String coverage, String definition, int leaseSeconds, Instant measuredAt) { }
}
