package com.spaceagent.platform.identity.api;

/**
 * Public result of a successful identity authentication.
 */
public record AuthenticatedIdentityView(
        String userId,
        String tenantId,
        String username,
        String displayName,
        String tenantRole) {
}
