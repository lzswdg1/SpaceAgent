package com.spaceagent.platform.identity.api;

public record ProvisionedOrganizationView(
        OrganizationView organization,
        UserView user,
        OrganizationMembershipView membership) {
}
