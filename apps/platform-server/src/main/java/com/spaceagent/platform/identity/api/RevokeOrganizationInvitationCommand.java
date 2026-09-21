package com.spaceagent.platform.identity.api;

public record RevokeOrganizationInvitationCommand(
        String organizationId,
        String invitationId,
        String actorUserId) {
}
