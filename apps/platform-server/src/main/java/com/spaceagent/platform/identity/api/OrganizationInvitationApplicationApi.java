package com.spaceagent.platform.identity.api;

import java.util.List;

public interface OrganizationInvitationApplicationApi {

    OrganizationInvitationCreatedView createInvitation(
            CreateOrganizationInvitationCommand command);

    List<OrganizationInvitationView> listInvitations(String organizationId, String actorUserId);

    OrganizationInvitationView revokeInvitation(RevokeOrganizationInvitationCommand command);

    OrganizationInvitationPreviewView previewInvitation(String token);

    AcceptedOrganizationInvitationView acceptInvitation(
            AcceptOrganizationInvitationCommand command);
}
