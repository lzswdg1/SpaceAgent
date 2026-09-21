package com.spaceagent.platform.identity.api;

public record OrganizationSummaryView(
        OrganizationView organization,
        OrganizationMembershipView membership) {
}
