package com.spaceagent.platform.identity.api;

import java.util.List;

/** Product-facing Organization lifecycle over the existing Tenant security boundary. */
public interface OrganizationApplicationApi {

    ProvisionedOrganizationView provisionPersonalOrganization(
            ProvisionPersonalOrganizationCommand command);

    OrganizationView createOrganization(CreateOrganizationCommand command);

    List<OrganizationSummaryView> listOrganizations(String userId);

    OrganizationView getOrganization(String organizationId, String userId);

    List<OrganizationMembershipView> listMembers(String organizationId, String userId);

    OrganizationMembershipView addMember(AddOrganizationMemberCommand command);

    void removeMember(RemoveOrganizationMemberCommand command);

    OrganizationView transferOwnership(TransferOrganizationOwnershipCommand command);

    OrganizationLeaveView leaveOrganization(LeaveOrganizationCommand command);
}
