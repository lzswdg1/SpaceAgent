package com.spaceagent.platform.identity.api;

/** Current actor authority, separate from immutable Run capability/configuration snapshots. */
public interface IdentityExecutionAuthorizationApi {
    void requireActiveActor(String tenantId, String userId, boolean write);
}
