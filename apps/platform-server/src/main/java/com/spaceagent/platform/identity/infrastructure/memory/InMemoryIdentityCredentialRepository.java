package com.spaceagent.platform.identity.infrastructure.memory;

import com.spaceagent.platform.identity.domain.IdentityCredentialRepository;
import com.spaceagent.platform.identity.domain.UserCredential;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory identity credential store for tests and local development.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryIdentityCredentialRepository implements IdentityCredentialRepository {

    private final Map<String, UserCredential> byUsername = new ConcurrentHashMap<>();
    private final Map<String, UserCredential> byUserId = new ConcurrentHashMap<>();

    @Override
    public Optional<UserCredential> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public Optional<UserCredential> findByUserId(String userId) {
        return Optional.ofNullable(byUserId.get(userId));
    }

    @Override
    public void save(UserCredential credential) {
        byUsername.put(credential.username(), credential);
        byUserId.put(credential.userId(), credential);
    }

    @Override
    public void updatePassword(String userId, String passwordHash) {
        byUserId.computeIfPresent(userId, (id, current) -> {
            UserCredential updated = new UserCredential(
                    current.userId(),
                    current.username(),
                    passwordHash,
                    current.createdAt(),
                    Instant.now());
            byUsername.put(current.username(), updated);
            return updated;
        });
    }
}
