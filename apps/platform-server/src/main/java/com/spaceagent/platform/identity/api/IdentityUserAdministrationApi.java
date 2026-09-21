package com.spaceagent.platform.identity.api;

import java.time.Instant;

public interface IdentityUserAdministrationApi {
    CreatedPendingUser createPendingUser(CreatePendingUserCommand command);

    UserLifecycleResult suspendUser(UserLifecycleCommand command);

    UserLifecycleResult restoreUser(UserLifecycleCommand command);

    UserLifecycleResult updateUser(UserProfileCommand command);

    UserLifecycleResult revokeSessions(UserLifecycleCommand command);

    PasswordReset issuePasswordReset(UserLifecycleCommand command);
    record PasswordReset(String userId, String token, Instant expiresAt) { }

    record UserProfileCommand(String userId, String displayName, String actorId, String reason) { }

    record CreatePendingUserCommand(String loginName, String displayName, String organizationName,
                                    String organizationSlug, String actorId) {
    }

    record CreatedPendingUser(String userId, String organizationId, String status,
                              String activationToken, Instant activationExpiresAt) {
    }

    record UserLifecycleCommand(String userId, String actorId, String reason) {
    }

    record UserLifecycleResult(String userId, String status, Instant updatedAt,
                               boolean sessionsRevoked) {
    }
}
