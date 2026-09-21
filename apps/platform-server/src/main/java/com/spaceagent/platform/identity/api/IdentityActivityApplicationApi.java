package com.spaceagent.platform.identity.api;

public interface IdentityActivityApplicationApi extends IdentityActorStatusPort {
    void recordLoginSucceeded(String userId, String subject, String clientType);

    void recordLoginFailed(String userId, String subject, String safeErrorCode, String clientType);

    void markSeen(String userId, String remoteAddress, String userAgent, String clientType);

    boolean isUserActive(String userId);
}
