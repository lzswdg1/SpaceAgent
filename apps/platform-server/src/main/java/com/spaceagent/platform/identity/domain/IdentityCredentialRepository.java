package com.spaceagent.platform.identity.domain;

import java.util.Optional;

/**
 * Domain persistence port for username/password credentials.
 */
public interface IdentityCredentialRepository {

    Optional<UserCredential> findByUsername(String username);

    Optional<UserCredential> findByUserId(String userId);

    void save(UserCredential credential);

    void updatePassword(String userId, String passwordHash);
}
