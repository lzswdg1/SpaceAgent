package com.spaceagent.platform.identity.domain;

import java.time.Instant;
import java.util.UUID;

public interface IdentityActivityRepository {
    void recordLoginEvent(
            UUID id,
            String userId,
            String subjectHash,
            boolean succeeded,
            String clientType,
            String ipHash,
            String userAgentHash,
            String safeErrorCode,
            Instant occurredAt);

    void recordSuccessfulLogin(
            String userId,
            String clientType,
            String ipHash,
            String userAgentHash,
            Instant at);

    void markSeenIfDue(
            String userId,
            String clientType,
            String ipHash,
            String userAgentHash,
            Instant at,
            Instant updateBefore);

    boolean isUserActive(String userId);
}
