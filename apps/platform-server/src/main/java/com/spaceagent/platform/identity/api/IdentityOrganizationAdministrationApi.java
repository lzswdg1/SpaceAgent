package com.spaceagent.platform.identity.api;

import java.time.Instant;

public interface IdentityOrganizationAdministrationApi {
    OrganizationResult create(CreateOrganizationCommand command);

    OrganizationResult update(UpdateOrganizationCommand command);

    OrganizationResult requestDeletion(DeleteOrganizationCommand command);

    MembershipResult addMember(AddMemberCommand command);

    MembershipResult updateMemberRole(UpdateMemberRoleCommand command);

    MembershipResult removeMember(RemoveMemberCommand command);

    OrganizationResult transferOwnership(TransferOwnershipCommand command);

    record CreateOrganizationCommand(String ownerUserId, String name, String slug,
                                     String actorId, String reason) {
    }

    record UpdateOrganizationCommand(String organizationId, String name, String slug,
                                     String actorId, String reason) {
    }

    record DeleteOrganizationCommand(String organizationId, String actorId, String reason) {
    }

    record AddMemberCommand(String organizationId, String userId, String role,
                            String actorId, String reason) {
    }

    record UpdateMemberRoleCommand(String organizationId, String userId, String role,
                                   String actorId, String reason) {
    }

    record RemoveMemberCommand(String organizationId, String userId,
                               String actorId, String reason) {
    }

    record TransferOwnershipCommand(String organizationId, String newOwnerUserId,
                                    String actorId, String reason) {
    }

    record OrganizationResult(String organizationId, String name, String slug,
                              String creatorUserId, String status, long activeMembers,
                              Instant createdAt, Instant updatedAt,
                              Instant deletionRequestedAt) {
    }

    record MembershipResult(String organizationId, String userId, String role, String status,
                            Instant joinedAt, Instant updatedAt) {
    }
}
