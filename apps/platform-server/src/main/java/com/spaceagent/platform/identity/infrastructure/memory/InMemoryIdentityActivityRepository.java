package com.spaceagent.platform.identity.infrastructure.memory;

import com.spaceagent.platform.identity.domain.IdentityActivityRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryIdentityActivityRepository implements IdentityActivityRepository {
    private final Set<String> observedUsers = ConcurrentHashMap.newKeySet();

    @Override
    public void recordLoginEvent(UUID id, String userId, String subjectHash, boolean succeeded,
                                 String clientType, String ipHash, String userAgentHash,
                                 String safeErrorCode, Instant occurredAt) {
        if (userId != null && succeeded) observedUsers.add(userId);
    }

    @Override
    public void recordSuccessfulLogin(String userId, String clientType, String ipHash,
                                      String userAgentHash, Instant at) {
        observedUsers.add(userId);
    }

    @Override
    public void markSeenIfDue(String userId, String clientType, String ipHash, String userAgentHash,
                              Instant at, Instant updateBefore) {
        observedUsers.add(userId);
    }

    @Override
    public boolean isUserActive(String userId) {
        return true;
    }
}
