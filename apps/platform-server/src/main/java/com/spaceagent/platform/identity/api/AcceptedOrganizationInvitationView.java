package com.spaceagent.platform.identity.api;

public record AcceptedOrganizationInvitationView(
        String invitationId,
        String organizationId,
        OrganizationMembershipView membership) {
}
