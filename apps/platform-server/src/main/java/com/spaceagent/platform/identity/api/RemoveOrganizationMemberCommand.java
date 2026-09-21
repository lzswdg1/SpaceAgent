package com.spaceagent.platform.identity.api;

public record RemoveOrganizationMemberCommand(
        String actorUserId,
        String organizationId,
        String memberUserId) {
}
