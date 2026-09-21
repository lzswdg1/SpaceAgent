package com.spaceagent.platform.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IdentitySystemAdministrationQuery {
    OverviewRow overview(Instant registeredSince, Instant loginSince, Instant recentlyActiveSince, Instant now);

    PageRows<UserRow> users(int offset, int limit, String query, String status, UserSort sort, Instant now);

    Optional<UserRow> user(String userId, Instant now);

    List<MembershipRow> memberships(String userId);

    PageRows<OrganizationRow> organizations(int offset, int limit, String query, String status);

    boolean organizationExists(String organizationId);

    PageRows<OrganizationMemberRow> organizationMembers(
            String organizationId, int offset, int limit, String query,
            String membershipStatus, String role);

    Optional<DeletionEvidenceRow> deletionEvidence(String userId);

    enum UserSort { CREATED_DESC, LAST_LOGIN_DESC, LAST_SEEN_DESC, LOGIN_ASC }

    record PageRows<T>(List<T> items, long total) {
        public PageRows { items = items == null ? List.of() : List.copyOf(items); }
    }

    record OverviewRow(long totalUsers, long pendingUsers, long activeUsers, long suspendedUsers,
                       long deletionPendingUsers, long deletedUsers, long registeredInWindow,
                       long uniqueSuccessfulLoginsInWindow, long recentlyActiveUsers,
                       long activeRefreshSessions, long activeOrganizations,
                       long deletingOrganizations) {
    }

    record UserRow(String id, String primaryOrganizationId, String loginName, String displayName,
                   String status, boolean mustChangePassword, Instant createdAt, Instant updatedAt,
                   Instant lastLoginAt, Instant lastSeenAt, long successfulLoginCount,
                   long activeRefreshSessions, long membershipCount) {
    }

    record MembershipRow(String organizationId, String organizationName, String organizationStatus,
                         String role, String membershipStatus, Instant joinedAt) {
    }

    record OrganizationRow(String id, String name, String slug, String status, String creatorUserId,
                           String creatorDisplayName, long activeMembers, Instant createdAt,
                           Instant updatedAt, Instant deletionRequestedAt) {
    }

    record OrganizationMemberRow(String organizationId, String userId, String loginName,
                                 String displayName, String userStatus, String role,
                                 String membershipStatus, Instant joinedAt, Instant updatedAt) {
    }

    record DeletionEvidenceRow(String userId, String status, long activeMemberships,
                               List<OwnedOrganizationRow> ownedOrganizations) {
    }

    record OwnedOrganizationRow(String organizationId, String name, String status,
                                long activeMembers) {
    }
}
