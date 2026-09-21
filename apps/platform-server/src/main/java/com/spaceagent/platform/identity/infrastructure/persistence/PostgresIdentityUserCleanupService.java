package com.spaceagent.platform.identity.infrastructure.persistence;

import com.spaceagent.platform.identity.api.IdentityUserCleanupApplicationApi;
import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi;
import com.spaceagent.platform.identity.domain.IdentityRepository;
import com.spaceagent.platform.identity.domain.OrganizationCleanupJobState;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.platform.identity.domain.UserCleanupRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresIdentityUserCleanupService implements IdentityUserCleanupApplicationApi {
    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;
    private final OrganizationCleanupApplicationApi organizationCleanup;
    private final UserCleanupRepository userCleanup;
    private final TimeProvider time;

    public PostgresIdentityUserCleanupService(
            JdbcTemplate jdbc,
            IdentityRepository identities,
            OrganizationCleanupApplicationApi organizationCleanup,
            UserCleanupRepository userCleanup,
            TimeProvider time) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.organizationCleanup = organizationCleanup;
        this.userCleanup = userCleanup;
        this.time = time;
    }

    @Override
    @Transactional
    public void freezeUser(String userId) {
        String status = jdbc.query("SELECT status FROM platform_users WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString("status"), userId).stream().findFirst()
                .orElseThrow(PostgresIdentityUserCleanupService::notFound);
        if (!"DELETION_PENDING".equals(status) && !"DELETED".equals(status)) {
            throw conflict("User is not pending deletion", "USER_CLEANUP_STATUS_CONFLICT");
        }
        jdbc.update("""
                UPDATE platform_refresh_tokens SET revoked_at = COALESCE(revoked_at, clock_timestamp())
                WHERE user_id = ?
                """, userId);
    }

    @Override
    @Transactional
    public MembershipResolutionView resolveMemberships(String userId) {
        Instant now = time.now();
        var memberships = identities.findMembershipsByUserId(userId);
        List<String> ownedOrganizations = new ArrayList<>();
        for (var membership : memberships) {
            var organization = identities.findTenantByIdForUpdate(membership.tenantId()).orElse(null);
            if (organization == null || organization.status() == TenantStatus.DELETED) continue;
            if (membership.role() == TenantRole.OWNER) {
                long activeMembers = identities.countActiveMemberships(organization.id());
                if (membership.status() == TenantMembershipStatus.ACTIVE && activeMembers > 1L) {
                    throw conflict("Organization ownership must be transferred before User deletion",
                            "USER_CLEANUP_OWNERSHIP_TRANSFER_REQUIRED");
                }
                if (membership.status() == TenantMembershipStatus.ACTIVE) {
                    identities.saveMembership(membership.suspend(now));
                }
                if (organization.status() == TenantStatus.ACTIVE) {
                    organization = organization.markDeleting(now);
                    identities.saveTenant(organization);
                }
                if (organization.status() == TenantStatus.DELETING) {
                    organizationCleanup.enqueue(organization.id());
                    ownedOrganizations.add(organization.id());
                }
            } else if (membership.status() == TenantMembershipStatus.ACTIVE) {
                identities.saveMembership(membership.suspend(now));
            }
        }

        long pending = 0L;
        Instant retryAt = now.plusSeconds(1);
        for (String organizationId : ownedOrganizations) {
            var job = organizationCleanup.findJob(organizationId).orElseThrow();
            if (job.state() != OrganizationCleanupJobState.COMPLETED) {
                pending++;
                Instant candidate = job.leaseUntil() != null ? job.leaseUntil().plusSeconds(1)
                        : job.nextAttemptAt();
                if (candidate != null && candidate.isAfter(retryAt)) retryAt = candidate;
            }
        }
        return new MembershipResolutionView(pending == 0L, pending == 0L ? null : retryAt, pending);
    }

    @Override
    @Transactional
    public void finalizeUser(String userId) {
        String status = jdbc.query("SELECT status FROM platform_users WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString("status"), userId).stream().findFirst()
                .orElseThrow(PostgresIdentityUserCleanupService::notFound);
        if ("DELETED".equals(status)) return;
        if (!"DELETION_PENDING".equals(status)) {
            throw conflict("User is not ready for finalization", "USER_CLEANUP_STATUS_CONFLICT");
        }
        if (userCleanup.countIncompleteSteps(userId) > 1L) {
            throw conflict("Owner cleanup steps are incomplete", "USER_CLEANUP_STEPS_INCOMPLETE");
        }
        Long unresolved = jdbc.queryForObject("""
                SELECT count(*) FROM platform_tenant_memberships membership
                JOIN platform_tenants tenant ON tenant.id = membership.tenant_id
                WHERE membership.user_id = ?
                  AND (membership.status = 'ACTIVE' OR
                    (membership.tenant_role = 'OWNER' AND tenant.status <> 'DELETED'))
                """, Long.class, userId);
        if (unresolved != null && unresolved > 0L) {
            throw conflict("User Organization resolution is incomplete",
                    "USER_CLEANUP_ORGANIZATION_INCOMPLETE");
        }

        jdbc.update("DELETE FROM platform_refresh_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM platform_access_token_revocations WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM platform_user_credentials WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM platform_user_activation_tokens WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM platform_user_profiles WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM platform_user_activity WHERE user_id = ?", userId);
        jdbc.update("UPDATE platform_auth_events SET user_id = NULL WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM platform_tenant_memberships WHERE user_id = ?", userId);
        jdbc.update("""
                UPDATE platform_users
                   SET external_id = ?, display_name = 'Deleted User', status = 'DELETED',
                       must_change_password = FALSE,
                       deletion_requested_at = COALESCE(deletion_requested_at, clock_timestamp()),
                       deleted_at = clock_timestamp(), updated_at = clock_timestamp()
                 WHERE id = ? AND status = 'DELETION_PENDING'
                """, "deleted-" + userId, userId);
    }

    private static BusinessException notFound() {
        return new BusinessException("User not found", HttpStatus.NOT_FOUND,
                "SYSTEM_ADMIN_USER_NOT_FOUND");
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }
}
