package com.spaceagent.platform.identity.api;

/** Minimal current-status authority for owner-private operations without an Organization scope. */
public interface IdentityActorStatusPort {
    boolean isUserActive(String userId);
}
