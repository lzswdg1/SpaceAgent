package com.spaceagent.platform.identity.api;

import java.time.Instant;

public interface IdentityUserCleanupApplicationApi {
    void freezeUser(String userId);

    MembershipResolutionView resolveMemberships(String userId);

    void finalizeUser(String userId);

    record MembershipResolutionView(boolean ready, Instant retryAt,
                                    long pendingOrganizationCleanups) {
    }
}
