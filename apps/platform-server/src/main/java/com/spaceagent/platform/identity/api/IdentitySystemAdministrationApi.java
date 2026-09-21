package com.spaceagent.platform.identity.api;

import com.spaceagent.platform.shared.api.SystemAdministrationPage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IdentitySystemAdministrationApi {
    IdentityOverview overview(Instant registeredSince, Instant loginSince, Instant recentlyActiveSince);

    SystemAdministrationPage<UserSummary> users(
            int page, int pageSize, String query, String status, String sort);

    Optional<UserDetail> user(String userId);

    SystemAdministrationPage<OrganizationSummary> organizations(
            int page, int pageSize, String query, String status);

    SystemAdministrationPage<OrganizationMemberSummary> organizationMembers(
            String organizationId, int page, int pageSize, String query,
            String membershipStatus, String role);

    IdentityDeletionEvidence deletionEvidence(String userId);

    record IdentityOverview(
            long totalUsers,
            long pendingUsers,
            long activeUsers,
            long suspendedUsers,
            long deletionPendingUsers,
            long deletedUsers,
            long registeredInWindow,
            long uniqueSuccessfulLoginsInWindow,
            long recentlyActiveUsers,
            long activeRefreshSessions,
            long activeOrganizations,
            long deletingOrganizations) {
    }

    record UserSummary(
            String id,
            String primaryOrganizationId,
            String loginName,
            String displayName,
            String status,
            boolean mustChangePassword,
            Instant createdAt,
            Instant updatedAt,
            Instant lastLoginAt,
            Instant lastSeenAt,
            long successfulLoginCount,
            long activeRefreshSessions,
            long membershipCount) {
    }

    record UserDetail(UserSummary user, List<UserMembership> memberships) {
        public UserDetail {
            memberships = memberships == null ? List.of() : List.copyOf(memberships);
        }
    }

    record UserMembership(
            String organizationId,
            String organizationName,
            String organizationStatus,
            String role,
            String membershipStatus,
            Instant joinedAt) {
    }

    record OrganizationSummary(
            String id,
            String name,
            String slug,
            String status,
            String creatorUserId,
            String creatorDisplayName,
            long activeMembers,
            Instant createdAt,
            Instant updatedAt,
            Instant deletionRequestedAt) {
    }

    record OrganizationMemberSummary(
            String organizationId,
            String userId,
            String loginName,
            String displayName,
            String userStatus,
            String role,
            String membershipStatus,
            Instant joinedAt,
            Instant updatedAt) {
    }

    record IdentityDeletionEvidence(String userId, String status, long activeMemberships,
                                    List<OwnedOrganization> ownedOrganizations) {
        public IdentityDeletionEvidence {
            ownedOrganizations = ownedOrganizations == null ? List.of() : List.copyOf(ownedOrganizations);
        }
    }

    record OwnedOrganization(String organizationId, String name, String status,
                             long activeMembers) {
    }
}
