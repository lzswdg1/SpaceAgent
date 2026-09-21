package com.spaceagent.platform.identity.api;

public record TransferOrganizationOwnershipCommand(
        String actorUserId,
        String organizationId,
        String newOwnerUserId) {
}
