package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.IdentitySystemAdministrationApi;
import com.spaceagent.platform.identity.domain.IdentitySystemAdministrationQuery;
import com.spaceagent.platform.shared.api.SystemAdministrationPage;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional(readOnly = true)
public class IdentitySystemAdministrationService implements IdentitySystemAdministrationApi {
    private final IdentitySystemAdministrationQuery query;
    private final TimeProvider timeProvider;

    public IdentitySystemAdministrationService(
            IdentitySystemAdministrationQuery query,
            TimeProvider timeProvider) {
        this.query = query;
        this.timeProvider = timeProvider;
    }

    @Override
    public IdentityOverview overview(
            Instant registeredSince,
            Instant loginSince,
            Instant recentlyActiveSince) {
        var row = query.overview(registeredSince, loginSince, recentlyActiveSince, timeProvider.now());
        return new IdentityOverview(row.totalUsers(), row.pendingUsers(), row.activeUsers(),
                row.suspendedUsers(), row.deletionPendingUsers(), row.deletedUsers(),
                row.registeredInWindow(), row.uniqueSuccessfulLoginsInWindow(),
                row.recentlyActiveUsers(), row.activeRefreshSessions(), row.activeOrganizations(),
                row.deletingOrganizations());
    }

    @Override
    public SystemAdministrationPage<UserSummary> users(
            int page, int pageSize, String text, String status, String sort) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        String safeStatus = status(status);
        var rows = query.users(safePage * safeSize, safeSize, bounded(text, 120), safeStatus,
                sort(sort), timeProvider.now());
        return new SystemAdministrationPage<>(rows.items().stream().map(this::user).toList(),
                safePage, safeSize, rows.total(), timeProvider.now());
    }

    @Override
    public Optional<UserDetail> user(String userId) {
        return query.user(userId, timeProvider.now()).map(row -> new UserDetail(user(row),
                query.memberships(userId).stream().map(value -> new UserMembership(
                        value.organizationId(), value.organizationName(), value.organizationStatus(),
                        value.role(), value.membershipStatus(), value.joinedAt())).toList()));
    }

    @Override
    public SystemAdministrationPage<OrganizationSummary> organizations(
            int page, int pageSize, String text, String status) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.organizations(safePage * safeSize, safeSize, bounded(text, 120),
                organizationStatus(status));
        return new SystemAdministrationPage<>(rows.items().stream().map(value ->
                new OrganizationSummary(value.id(), value.name(), value.slug(), value.status(),
                        value.creatorUserId(), value.creatorDisplayName(), value.activeMembers(),
                        value.createdAt(), value.updatedAt(), value.deletionRequestedAt())).toList(),
                safePage, safeSize, rows.total(), timeProvider.now());
    }

    @Override
    public SystemAdministrationPage<OrganizationMemberSummary> organizationMembers(
            String organizationId, int page, int pageSize, String text,
            String membershipStatus, String role) {
        if (organizationId == null || organizationId.isBlank()
                || !query.organizationExists(organizationId.trim())) {
            throw new com.spaceagent.shared.exception.BusinessException(
                    "Organization not found", org.springframework.http.HttpStatus.NOT_FOUND,
                    "ORGANIZATION_NOT_FOUND");
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        var rows = query.organizationMembers(organizationId.trim(), safePage * safeSize, safeSize,
                bounded(text, 120), membershipStatus(membershipStatus), membershipRole(role));
        return new SystemAdministrationPage<>(rows.items().stream().map(value ->
                new OrganizationMemberSummary(value.organizationId(), value.userId(),
                        value.loginName(), value.displayName(), value.userStatus(), value.role(),
                        value.membershipStatus(), value.joinedAt(), value.updatedAt())).toList(),
                safePage, safeSize, rows.total(), timeProvider.now());
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = com.spaceagent.shared.exception.BusinessException.class)
    public IdentityDeletionEvidence deletionEvidence(String userId) {
        var row = query.deletionEvidence(userId).orElseThrow(() ->
                new com.spaceagent.shared.exception.BusinessException("User not found",
                        org.springframework.http.HttpStatus.NOT_FOUND, "SYSTEM_ADMIN_USER_NOT_FOUND"));
        return new IdentityDeletionEvidence(row.userId(), row.status(), row.activeMemberships(),
                row.ownedOrganizations().stream().map(value -> new OwnedOrganization(
                        value.organizationId(), value.name(), value.status(),
                        value.activeMembers())).toList());
    }

    private UserSummary user(IdentitySystemAdministrationQuery.UserRow row) {
        return new UserSummary(row.id(), row.primaryOrganizationId(), row.loginName(), row.displayName(),
                row.status(), row.mustChangePassword(), row.createdAt(), row.updatedAt(),
                row.lastLoginAt(), row.lastSeenAt(), row.successfulLoginCount(),
                row.activeRefreshSessions(), row.membershipCount());
    }

    private static IdentitySystemAdministrationQuery.UserSort sort(String value) {
        if (value == null || value.isBlank()) return IdentitySystemAdministrationQuery.UserSort.CREATED_DESC;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "lastlogin", "last_login_desc" -> IdentitySystemAdministrationQuery.UserSort.LAST_LOGIN_DESC;
            case "lastseen", "last_seen_desc" -> IdentitySystemAdministrationQuery.UserSort.LAST_SEEN_DESC;
            case "login", "login_asc" -> IdentitySystemAdministrationQuery.UserSort.LOGIN_ASC;
            default -> IdentitySystemAdministrationQuery.UserSort.CREATED_DESC;
        };
    }

    private static String status(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PENDING_ACTIVATION", "ACTIVE", "SUSPENDED", "DELETION_PENDING", "DELETED" -> normalized;
            default -> null;
        };
    }

    private static String organizationStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ACTIVE", "SUSPENDED", "DELETING", "DELETED" -> normalized;
            default -> null;
        };
    }

    private static String membershipStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ACTIVE", "SUSPENDED" -> normalized;
            default -> null;
        };
    }

    private static String membershipRole(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "OWNER", "ADMIN", "MEMBER", "VIEWER" -> normalized;
            default -> null;
        };
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(maximum, trimmed.length()));
    }
}
