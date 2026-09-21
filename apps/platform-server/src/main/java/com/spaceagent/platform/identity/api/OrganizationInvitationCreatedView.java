package com.spaceagent.platform.identity.api;

/** The plaintext token is returned exactly once and is never persisted. */
public record OrganizationInvitationCreatedView(
        OrganizationInvitationView invitation,
        String token) {
}
