package com.spaceagent.platform.identity.api;

public record AcceptOrganizationInvitationCommand(String token, String userId) {
}
