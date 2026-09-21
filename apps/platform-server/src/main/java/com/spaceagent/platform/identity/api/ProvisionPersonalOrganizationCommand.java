package com.spaceagent.platform.identity.api;

public record ProvisionPersonalOrganizationCommand(
        String externalId,
        String displayName,
        String organizationName,
        String organizationSlug) {
}
