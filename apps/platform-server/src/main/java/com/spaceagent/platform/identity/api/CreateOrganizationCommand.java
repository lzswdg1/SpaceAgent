package com.spaceagent.platform.identity.api;

public record CreateOrganizationCommand(
        String actorUserId,
        String name,
        String slug) {
}
